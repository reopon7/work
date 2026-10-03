package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * V44 Etoland native video renderer.
 *
 * Unlike V38-V43, the native TextureView is no longer attached to the Activity
 * root. It lives in the WebView's own ViewGroupOverlay coordinate space.
 * That makes clipping, tab visibility and page-relative movement naturally
 * follow the WebView instead of simulating those relationships at Activity
 * level.
 */
final class NativeVideoOverlay {
    private static final String TAG = "HabitNativeV44";

    private static final WeakHashMap<WebView, State> STATES =
            new WeakHashMap<WebView, State>();

    private NativeVideoOverlay() {}

    static synchronized boolean isStable(WebView webView) {
        State state = STATES.get(webView);
        return state != null &&
               state.src != null &&
               state.src.length() > 0 &&
               state.prepared &&
               state.nativeOwned;
    }

    static synchronized void update(final WebView webView) {
        if (webView == null || Build.VERSION.SDK_INT < 21) return;

        if (!isEligible(webView)) {
            hide(webView);
            return;
        }

        final String expectedUrl = webView.getUrl();

        try {
            webView.evaluateJavascript(PROBE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    String currentUrl = null;
                    try { currentUrl = webView.getUrl(); } catch (Throwable ignored) {}

                    if (!isEligible(webView) ||
                        expectedUrl == null ||
                        currentUrl == null ||
                        !expectedUrl.equals(currentUrl)) {
                        hide(webView);
                        return;
                    }

                    applyProbe(webView, value);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            hide(webView);
        }
    }

    static synchronized void hide(WebView webView) {
        State state = STATES.get(webView);
        if (state == null) return;

        pauseNative(state);

        if (state.container != null) {
            state.container.setVisibility(View.GONE);
        }
        state.visible = false;
    }

    static synchronized void hideAll(Activity activity) {
        if (activity == null) return;
        for (Map.Entry<WebView, State> entry : STATES.entrySet()) {
            State state = entry.getValue();
            if (state != null && state.activity == activity) {
                hide(entry.getKey());
            }
        }
    }

    static synchronized void destroy(WebView webView) {
        State state = STATES.remove(webView);
        if (state == null) return;

        try {
            if (state.scrollListener != null) {
                ViewTreeObserver observer = webView.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnScrollChangedListener(state.scrollListener);
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (state.layoutListener != null) {
                webView.removeOnLayoutChangeListener(state.layoutListener);
            }
        } catch (Throwable ignored) {}

        // Restore the HTML video only while the same document is still alive.
        try {
            String current = webView.getUrl();
            if (state.nativeOwned &&
                current != null &&
                current.equals(state.pageUrl) &&
                webView.getWindowToken() != null) {
                webView.evaluateJavascript(RESTORE_JS, null);
            }
        } catch (Throwable ignored) {}

        releasePlayer(state);

        try {
            if (state.hostOverlay != null && state.container != null) {
                state.hostOverlay.remove(state.container);
            }
        } catch (Throwable ignored) {}
    }

    static synchronized void destroyAll(Activity activity) {
        if (activity == null) return;
        WebView[] views = STATES.keySet().toArray(new WebView[0]);
        for (WebView view : views) {
            State state = STATES.get(view);
            if (state != null && state.activity == activity) {
                destroy(view);
            }
        }
    }

    private static void applyProbe(WebView webView, String value) {
        try {
            if (!isEligible(webView)) {
                hide(webView);
                return;
            }

            if (value == null || "null".equals(value) || "undefined".equals(value)) {
                hide(webView);
                return;
            }

            JSONObject o = new JSONObject(value);
            String src = o.optString("src", "");
            if (src.length() == 0) {
                hide(webView);
                return;
            }

            Activity activity = findActivity(webView.getContext());
            if (activity == null) return;

            State state;
            synchronized (NativeVideoOverlay.class) {
                state = STATES.get(webView);
                if (state == null) {
                    state = createState(activity, webView);
                    if (state == null) return;
                    STATES.put(webView, state);
                }
            }

            state.pageUrl = o.optString("page", "");
            state.docLeftCss = o.optDouble("docLeft", 0.0);
            state.docTopCss = o.optDouble("docTop", 0.0);
            state.widthCss = o.optDouble("width", 0.0);
            state.heightCss = o.optDouble("height", 0.0);
            state.viewportWidthCss = o.optDouble("viewportWidth", 0.0);
            state.pendingSeekMs =
                    Math.max(0, (int) Math.round(o.optDouble("currentTime", 0.0) * 1000.0));

            boolean ownedByJs = o.optBoolean("owned", false);
            state.pendingPlay = ownedByJs
                    ? o.optBoolean("wanted", true)
                    : !o.optBoolean("paused", true);
            state.pendingLoop = o.optBoolean("loop", false);

            if (state.widthCss < 2.0 ||
                state.heightCss < 2.0 ||
                state.viewportWidthCss < 1.0) {
                hide(webView);
                return;
            }

            refreshGeometry(webView, state);

            if (!src.equals(state.src)) {
                state.src = src;
                state.prepared = false;
                state.nativeOwned = false;
                openPlayerIfReady(state);
            } else if (state.prepared) {
                applyNativePlayState(state);
            }

            if (!applyPosition(webView, state)) {
                hide(webView);
                return;
            }

            if (state.prepared && !state.nativeOwned) {
                requestOwnership(webView, state);
            }

            showVisual(state);
        } catch (Throwable t) {
            Log.e(TAG, "apply probe failed: " + value, t);
        }
    }

    private static State createState(
            Activity activity,
            final WebView webView) {

        final State state = new State();
        state.activity = activity;
        state.webView = webView;
        state.hostOverlay = webView.getOverlay();

        final PassthroughFrameLayout container =
                new PassthroughFrameLayout(activity);
        container.setBackgroundColor(0xff000000);
        container.setVisibility(View.GONE);
        container.setClipChildren(true);
        container.setClipToPadding(true);
        container.setClickable(false);
        container.setFocusable(false);

        final TextureView texture = new TextureView(activity);
        texture.setOpaque(true);
        texture.setClickable(false);
        texture.setFocusable(false);

        container.addView(texture, new FrameLayout.LayoutParams(1, 1));

        state.container = container;
        state.texture = texture;

        try {
            state.hostOverlay.add(container);
        } catch (Throwable t) {
            Log.e(TAG, "WebView overlay add failed", t);
            return null;
        }

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture surface, int width, int height) {
                try {
                    if (state.surface != null) state.surface.release();
                } catch (Throwable ignored) {}
                state.surface = new Surface(surface);
                openPlayerIfReady(state);
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                try {
                    if (state.player != null) {
                        state.player.setSurface(null);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (state.surface != null) state.surface.release();
                } catch (Throwable ignored) {}
                state.surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        state.positionRunnable = new Runnable() {
            @Override
            public void run() {
                state.positionPosted = false;

                if (!isEligible(webView)) {
                    hide(webView);
                    return;
                }

                if (applyPosition(webView, state)) {
                    showVisual(state);
                } else {
                    hide(webView);
                }
            }
        };

        state.scrollListener = new ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                schedulePosition(webView, state);
            }
        };

        try {
            webView.getViewTreeObserver()
                    .addOnScrollChangedListener(state.scrollListener);
        } catch (Throwable t) {
            Log.e(TAG, "scroll listener install failed", t);
        }

        state.layoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                refreshGeometry(webView, state);
                schedulePosition(webView, state);
            }
        };
        webView.addOnLayoutChangeListener(state.layoutListener);

        return state;
    }

    private static void schedulePosition(WebView webView, State state) {
        if (state.positionPosted || state.positionRunnable == null) return;
        state.positionPosted = true;
        try {
            webView.postOnAnimation(state.positionRunnable);
        } catch (Throwable t) {
            state.positionPosted = false;
            try { webView.post(state.positionRunnable); } catch (Throwable ignored) {}
        }
    }

    private static void refreshGeometry(WebView webView, State state) {
        if (state == null ||
            state.viewportWidthCss < 1.0 ||
            state.widthCss < 2.0 ||
            state.heightCss < 2.0) {
            return;
        }

        double scale = ((double) webView.getWidth()) / state.viewportWidthCss;

        state.baseLeft =
                (int) Math.round(state.docLeftCss * scale);
        state.baseTop =
                (int) Math.round(state.docTopCss * scale);
        state.fullWidth =
                Math.max(2, (int) Math.round(state.widthCss * scale));
        state.fullHeight =
                Math.max(2, (int) Math.round(state.heightCss * scale));

        if (state.layoutWidth != state.fullWidth ||
            state.layoutHeight != state.fullHeight) {
            layoutExact(state.container, 0, 0, state.fullWidth, state.fullHeight);
            state.layoutWidth = state.fullWidth;
            state.layoutHeight = state.fullHeight;
            applyTextureAspect(state);
        }

        state.geometryValid = true;
    }

    /**
     * WebView-overlay coordinates: no Activity-root coordinate conversion and
     * no clipBounds updates. The WebView itself clips its ViewGroupOverlay.
     */
    private static boolean applyPosition(WebView webView, State state) {
        if (!state.geometryValid) return false;

        int left = state.baseLeft - webView.getScrollX();
        int top = state.baseTop - webView.getScrollY();

        int right = left + state.fullWidth;
        int bottom = top + state.fullHeight;

        if (right <= 0 ||
            bottom <= 0 ||
            left >= webView.getWidth() ||
            top >= webView.getHeight()) {
            return false;
        }

        if (state.lastLeft != left) {
            state.container.setTranslationX(left);
            state.lastLeft = left;
        }

        if (state.lastTop != top) {
            state.container.setTranslationY(top);
            state.lastTop = top;
        }

        return true;
    }

    private static void showVisual(State state) {
        if (state == null || state.container == null) return;

        if (!state.visible) {
            state.container.setVisibility(View.VISIBLE);
            state.visible = true;
        }

        if (state.prepared && state.nativeOwned) {
            applyNativePlayState(state);
        }
    }

    private static void requestOwnership(
            final WebView webView,
            final State state) {

        if (state.ownershipPending || state.nativeOwned) return;
        state.ownershipPending = true;

        try {
            webView.evaluateJavascript(TAKE_OWNERSHIP_JS,
                    new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    state.ownershipPending = false;

                    if (value == null ||
                        "null".equals(value) ||
                        "undefined".equals(value)) {
                        return;
                    }

                    try {
                        JSONObject o = new JSONObject(value);
                        String src = o.optString("src", "");
                        if (!src.equals(state.src)) return;

                        state.pendingSeekMs =
                                Math.max(0, (int) Math.round(
                                        o.optDouble("currentTime", 0.0) * 1000.0));
                        state.pendingPlay =
                                o.optBoolean("wanted", state.pendingPlay);
                        state.nativeOwned = true;

                        if (state.player != null && state.prepared) {
                            try {
                                state.player.seekTo(state.pendingSeekMs);
                            } catch (Throwable ignored) {}
                            try {
                                state.player.setVolume(1.0f, 1.0f);
                            } catch (Throwable ignored) {}
                            applyNativePlayState(state);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "ownership result failed: " + value, t);
                    }
                }
            });
        } catch (Throwable t) {
            state.ownershipPending = false;
            Log.e(TAG, "ownership request failed", t);
        }
    }

    private static void openPlayerIfReady(final State state) {
        if (state == null ||
            state.src == null ||
            state.src.length() == 0 ||
            state.surface == null ||
            !state.surface.isValid()) {
            return;
        }

        releasePlayer(state);

        try {
            final MediaPlayer mp = new MediaPlayer();
            state.player = mp;
            state.prepared = false;

            HashMap<String, String> headers = new HashMap<String, String>();
            if (state.pageUrl != null && state.pageUrl.length() > 0) {
                headers.put("Referer", state.pageUrl);
            }
            try {
                String cookie =
                        CookieManager.getInstance().getCookie(state.src);
                if (cookie != null && cookie.length() > 0) {
                    headers.put("Cookie", cookie);
                }
            } catch (Throwable ignored) {}

            mp.setSurface(state.surface);

            // Before JS ownership succeeds, stay muted so the original HTML
            // video remains a safe fallback without double audio.
            mp.setVolume(0.0f, 0.0f);
            mp.setLooping(state.pendingLoop);
            mp.setDataSource(state.activity, Uri.parse(state.src), headers);

            mp.setOnVideoSizeChangedListener(
                    new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(
                        MediaPlayer player, int width, int height) {
                    state.videoWidth = width;
                    state.videoHeight = height;
                    applyTextureAspect(state);
                }
            });

            mp.setOnPreparedListener(
                    new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (state.player != player) return;

                    state.prepared = true;
                    state.videoWidth = player.getVideoWidth();
                    state.videoHeight = player.getVideoHeight();
                    applyTextureAspect(state);

                    try { player.seekTo(state.pendingSeekMs); }
                    catch (Throwable ignored) {}

                    if (state.nativeOwned) {
                        try { player.setVolume(1.0f, 1.0f); }
                        catch (Throwable ignored) {}
                        applyNativePlayState(state);
                    }

                    WebView webView = state.webView;
                    if (webView != null &&
                        isEligible(webView) &&
                        !state.nativeOwned) {
                        requestOwnership(webView, state);
                    }
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(
                        MediaPlayer player, int what, int extra) {
                    Log.e(TAG,
                            "native player error what=" + what +
                            " extra=" + extra +
                            " src=" + state.src);
                    return false;
                }
            });

            mp.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "open native player failed: " + state.src, t);
            releasePlayer(state);
        }
    }

    private static void applyNativePlayState(State state) {
        MediaPlayer mp = state.player;
        if (mp == null || !state.prepared || !state.nativeOwned) return;

        try {
            mp.setLooping(state.pendingLoop);
            mp.setVolume(1.0f, 1.0f);

            if (state.pendingPlay) {
                if (!mp.isPlaying()) mp.start();
            } else {
                if (mp.isPlaying()) mp.pause();
            }
        } catch (Throwable ignored) {}
    }

    private static void pauseNative(State state) {
        try {
            if (state != null &&
                state.player != null &&
                state.prepared &&
                state.player.isPlaying()) {
                state.player.pause();
            }
        } catch (Throwable ignored) {}
    }

    private static void releasePlayer(State state) {
        if (state == null) return;

        MediaPlayer old = state.player;
        state.player = null;
        state.prepared = false;

        if (old == null) return;

        try { old.setSurface(null); } catch (Throwable ignored) {}
        try { old.reset(); } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}
    }

    private static void applyTextureAspect(State state) {
        if (state == null ||
            state.texture == null ||
            state.layoutWidth < 2 ||
            state.layoutHeight < 2) {
            return;
        }

        int boxW = state.layoutWidth;
        int boxH = state.layoutHeight;
        int texW = boxW;
        int texH = boxH;
        int left = 0;
        int top = 0;

        if (state.videoWidth > 0 && state.videoHeight > 0) {
            double videoAspect =
                    (double) state.videoWidth / (double) state.videoHeight;
            double boxAspect =
                    (double) boxW / (double) boxH;

            if (videoAspect > boxAspect) {
                texW = boxW;
                texH = Math.max(
                        2,
                        (int) Math.round(boxW / videoAspect));
                top = (boxH - texH) / 2;
            } else {
                texH = boxH;
                texW = Math.max(
                        2,
                        (int) Math.round(boxH * videoAspect));
                left = (boxW - texW) / 2;
            }
        }

        if (state.textureWidth == texW &&
            state.textureHeight == texH &&
            state.textureLeft == left &&
            state.textureTop == top) {
            return;
        }

        layoutExact(
                state.texture,
                left,
                top,
                left + texW,
                top + texH);

        state.textureWidth = texW;
        state.textureHeight = texH;
        state.textureLeft = left;
        state.textureTop = top;
    }

    private static void layoutExact(
            View view,
            int left,
            int top,
            int right,
            int bottom) {

        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);

        view.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        height,
                        View.MeasureSpec.EXACTLY));

        view.layout(left, top, right, bottom);
    }

    private static boolean isEligible(WebView webView) {
        try {
            if (webView == null) return false;
            if (webView.getParent() == null) return false;
            if (webView.getWindowToken() == null) return false;
            if (!webView.isShown()) return false;
            if (webView.getVisibility() != View.VISIBLE) return false;
            if (webView.getWindowVisibility() != View.VISIBLE) return false;
            if (webView.getAlpha() <= 0.01f) return false;
            if (webView.getWidth() < 2 || webView.getHeight() < 2) return false;

            String url = webView.getUrl();
            return url != null && url.contains("etoland.co.kr");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Activity findActivity(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return c instanceof Activity ? (Activity) c : null;
    }

    private static final String PROBE_JS =
            "(function(){" +
            "try{" +
            " var a=document.getElementsByTagName('video'),best=null,bestArea=0;" +
            " for(var i=0;i<a.length;i++){" +
            "  var v=a[i],r=v.getBoundingClientRect();" +
            "  var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));" +
            "  var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
            "  var area=w*h;" +
            "  if(area>bestArea&&(v.currentSrc||v.src)){best=v;bestArea=area;}" +
            " }" +
            " if(!best)return null;" +
            " if(best.__hbNativeOwned && !best.paused){" +
            "  best.__hbNativeWanted=true;" +
            "  best.__hbNativeSelfPause=true;" +
            "  try{HTMLMediaElement.prototype.pause.call(best);}catch(e){}" +
            "  best.__hbNativeSelfPause=false;" +
            " }" +
            " var r=best.getBoundingClientRect();" +
            " return {" +
            "  src:String(best.currentSrc||best.src||'')," +
            "  page:String(location.href)," +
            "  docLeft:r.left+scrollX,docTop:r.top+scrollY," +
            "  width:r.width,height:r.height," +
            "  viewportWidth:innerWidth,viewportHeight:innerHeight," +
            "  currentTime:Number(best.currentTime||0)," +
            "  paused:!!best.paused,loop:!!best.loop," +
            "  owned:!!best.__hbNativeOwned," +
            "  wanted:best.__hbNativeOwned?!!best.__hbNativeWanted:!best.paused" +
            " };" +
            "}catch(e){return null;}" +
            "})();";

    private static final String TAKE_OWNERSHIP_JS =
            "(function(){" +
            "try{" +
            " var a=document.getElementsByTagName('video'),best=null,bestArea=0;" +
            " for(var i=0;i<a.length;i++){" +
            "  var v=a[i],r=v.getBoundingClientRect();" +
            "  var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));" +
            "  var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
            "  var area=w*h;" +
            "  if(area>bestArea&&(v.currentSrc||v.src)){best=v;bestArea=area;}" +
            " }" +
            " if(!best)return null;" +
            " if(!best.__hbNativeOwned){" +
            "  best.__hbNativeOwned=true;" +
            "  best.__hbNativeWanted=!best.paused;" +
            "  best.__hbNativeOldMuted=best.muted;" +
            "  best.__hbNativeOldControls=best.controls;" +
            "  best.__hbNativeOldOpacity=best.style.opacity||'';" +
            "  best.__hbNativeSelfPause=true;" +
            "  try{HTMLMediaElement.prototype.pause.call(best);}catch(e){}" +
            "  best.__hbNativeSelfPause=false;" +
            "  best.muted=true;" +
            "  best.controls=false;" +
            "  best.style.opacity='0.001';" +
            "  best.addEventListener('click',function(e){" +
            "   if(!best.__hbNativeOwned)return;" +
            "   best.__hbNativeWanted=!best.__hbNativeWanted;" +
            "   e.preventDefault();e.stopPropagation();" +
            "  },true);" +
            " }" +
            " return {" +
            "  src:String(best.currentSrc||best.src||'')," +
            "  currentTime:Number(best.currentTime||0)," +
            "  wanted:!!best.__hbNativeWanted" +
            " };" +
            "}catch(e){return null;}" +
            "})();";

    private static final String RESTORE_JS =
            "(function(){" +
            "try{" +
            " var a=document.getElementsByTagName('video');" +
            " for(var i=0;i<a.length;i++){" +
            "  var v=a[i];if(!v.__hbNativeOwned)continue;" +
            "  v.__hbNativeOwned=false;" +
            "  try{v.muted=!!v.__hbNativeOldMuted;}catch(e){}" +
            "  try{v.controls=!!v.__hbNativeOldControls;}catch(e){}" +
            "  try{v.style.opacity=v.__hbNativeOldOpacity||'';}catch(e){}" +
            " }" +
            "}catch(e){}" +
            "})();";

    private static final class PassthroughFrameLayout extends FrameLayout {
        PassthroughFrameLayout(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }
    }

    private static final class State {
        Activity activity;
        WebView webView;
        ViewGroupOverlay hostOverlay;
        PassthroughFrameLayout container;
        TextureView texture;
        Surface surface;
        MediaPlayer player;

        ViewTreeObserver.OnScrollChangedListener scrollListener;
        View.OnLayoutChangeListener layoutListener;
        Runnable positionRunnable;

        String src = "";
        String pageUrl = "";

        boolean prepared;
        boolean nativeOwned;
        boolean ownershipPending;
        boolean pendingPlay;
        boolean pendingLoop;
        boolean visible;
        boolean positionPosted;
        boolean geometryValid;

        int pendingSeekMs;

        double docLeftCss;
        double docTopCss;
        double widthCss;
        double heightCss;
        double viewportWidthCss;

        int videoWidth;
        int videoHeight;

        int baseLeft;
        int baseTop;
        int fullWidth;
        int fullHeight;

        int layoutWidth = -1;
        int layoutHeight = -1;
        int lastLeft = Integer.MIN_VALUE;
        int lastTop = Integer.MIN_VALUE;

        int textureWidth = -1;
        int textureHeight = -1;
        int textureLeft = Integer.MIN_VALUE;
        int textureTop = Integer.MIN_VALUE;
    }
}
