package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
 * V44 Etoland video visualizer.
 *
 * V43 still updated Activity-root clip bounds and ran visibility/player checks
 * while a fling was active. V44 creates one viewport layer clipped to the
 * actual WebView rectangle. Scrolling only translates the video child inside
 * that viewport; no screen-coordinate query, relayout, clip-bounds mutation,
 * JavaScript call or MediaPlayer call is needed on the scroll hot path.
 */
final class NativeVideoOverlay {
    private static final String TAG = "HabitNativeTexture";

    private static final WeakHashMap<WebView, State> STATES =
            new WeakHashMap<WebView, State>();

    private NativeVideoOverlay() {}

    static synchronized boolean hasOverlay(WebView webView) {
        State state = STATES.get(webView);
        return state != null && state.src != null && state.src.length() > 0;
    }

    static synchronized boolean isScrolling(WebView webView) {
        State state = STATES.get(webView);
        return state != null &&
                SystemClock.uptimeMillis() - state.lastScrollMs < 450L;
    }

    static synchronized void update(final WebView webView) {
        if (webView == null || Build.VERSION.SDK_INT < 19) return;

        if (!isEligible(webView)) {
            destroy(webView);
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
                        destroy(webView);
                        return;
                    }

                    applyProbe(webView, value);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            destroy(webView);
        }
    }

    static synchronized void hide(WebView webView) {
        State state = STATES.get(webView);
        if (state == null) return;

        pauseVisual(state);

        if (state.viewportLayer != null) {
            state.viewportLayer.setVisibility(View.GONE);
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

        try {
            if (state.settleRunnable != null) {
                webView.removeCallbacks(state.settleRunnable);
            }
        } catch (Throwable ignored) {}

        releasePlayer(state);

        try {
            if (state.surface != null) {
                state.surface.release();
                state.surface = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (state.viewportLayer != null &&
                state.viewportLayer.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.viewportLayer.getParent())
                        .removeView(state.viewportLayer);
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
                destroy(webView);
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
                    Math.max(0, (int) Math.round(
                            o.optDouble("currentTime", 0.0) * 1000.0));
            state.pendingAutoplay = !o.optBoolean("paused", true);
            state.pendingLoop = o.optBoolean("loop", false);

            if (state.widthCss < 2.0 ||
                state.heightCss < 2.0 ||
                state.viewportWidthCss < 1.0) {
                hide(webView);
                return;
            }

            if (!refreshViewportGeometry(webView, state)) {
                hide(webView);
                return;
            }

            if (!src.equals(state.src)) {
                state.src = src;
                state.prepared = false;
                openPlayerIfReady(state);
            } else if (state.prepared && state.player != null) {
                syncPlayer(state, false);
            }

            boolean inViewport = applyScrollTranslation(webView, state);
            setVideoVisibility(state, inViewport);

            if (!isEligible(webView)) {
                destroy(webView);
                return;
            }

            if (state.viewportLayer.getVisibility() != View.VISIBLE) {
                state.viewportLayer.setVisibility(View.VISIBLE);
                state.viewportLayer.bringToFront();
            }
            state.visible = true;
        } catch (Throwable t) {
            Log.e(TAG, "apply probe failed: " + value, t);
        }
    }

    private static State createState(Activity activity, final WebView webView) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;

        final ViewGroup root = (ViewGroup) content;
        final State state = new State();
        state.activity = activity;
        state.webView = webView;
        state.root = root;

        PassthroughFrameLayout viewport = new PassthroughFrameLayout(activity);
        viewport.setVisibility(View.GONE);
        viewport.setClipChildren(true);
        viewport.setClipToPadding(true);

        FrameLayout videoContainer = new FrameLayout(activity);
        videoContainer.setBackgroundColor(0xff000000);
        videoContainer.setClickable(false);
        videoContainer.setFocusable(false);

        final TextureView texture = new TextureView(activity);
        texture.setOpaque(true);
        texture.setClickable(false);
        texture.setFocusable(false);

        videoContainer.addView(texture, new FrameLayout.LayoutParams(1, 1));
        viewport.addView(videoContainer, new FrameLayout.LayoutParams(1, 1));
        root.addView(viewport, new ViewGroup.LayoutParams(1, 1));

        state.viewportLayer = viewport;
        state.videoContainer = videoContainer;
        state.texture = texture;

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
                    if (state.player != null) state.player.setSurface(null);
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

                if (!isCheaplyEligible(webView) || !state.geometryValid) {
                    setVideoVisibility(state, false);
                    return;
                }

                boolean inViewport = applyScrollTranslation(webView, state);
                setVideoVisibility(state, inViewport);
                scheduleSettle(webView, state);
            }
        };

        state.settleRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isCheaplyEligible(webView)) return;
                if (SystemClock.uptimeMillis() - state.lastScrollMs < 400L) {
                    scheduleSettle(webView, state);
                    return;
                }

                if (!state.inViewport) {
                    pauseVisual(state);
                } else if (state.prepared && state.pendingAutoplay) {
                    try {
                        if (!state.player.isPlaying()) state.player.start();
                    } catch (Throwable ignored) {}
                }
            }
        };

        state.scrollListener = new ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                state.lastScrollMs = SystemClock.uptimeMillis();
                schedulePosition(webView, state);
            }
        };

        try {
            webView.getViewTreeObserver().addOnScrollChangedListener(
                    state.scrollListener);
        } catch (Throwable t) {
            Log.e(TAG, "scroll listener install failed", t);
        }

        state.layoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (refreshViewportGeometry(webView, state)) {
                    schedulePosition(webView, state);
                }
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

    private static void scheduleSettle(WebView webView, State state) {
        if (state.settleRunnable == null) return;
        try {
            webView.removeCallbacks(state.settleRunnable);
            webView.postDelayed(state.settleRunnable, 450L);
        } catch (Throwable ignored) {}
    }

    /**
     * Costly screen-coordinate work. Called only on probe/layout changes.
     * The viewport itself is clipped by the ViewGroup, so the scroll path
     * never updates clip bounds.
     */
    private static boolean refreshViewportGeometry(WebView webView, State state) {
        try {
            if (state.viewportWidthCss < 1.0 ||
                state.widthCss < 2.0 ||
                state.heightCss < 2.0) {
                state.geometryValid = false;
                return false;
            }

            int[] webLoc = new int[2];
            int[] rootLoc = new int[2];
            webView.getLocationOnScreen(webLoc);
            state.root.getLocationOnScreen(rootLoc);

            Rect visibleScreen = new Rect();
            if (!webView.getGlobalVisibleRect(visibleScreen)) {
                state.geometryValid = false;
                return false;
            }

            int viewportLeft = visibleScreen.left - rootLoc[0];
            int viewportTop = visibleScreen.top - rootLoc[1];
            int viewportWidth = visibleScreen.width();
            int viewportHeight = visibleScreen.height();

            if (viewportWidth < 2 || viewportHeight < 2) {
                state.geometryValid = false;
                return false;
            }

            if (state.viewportWidth != viewportWidth ||
                state.viewportHeight != viewportHeight) {
                ViewGroup.LayoutParams lp = state.viewportLayer.getLayoutParams();
                lp.width = viewportWidth;
                lp.height = viewportHeight;
                state.viewportLayer.setLayoutParams(lp);
                state.viewportWidth = viewportWidth;
                state.viewportHeight = viewportHeight;
            }

            if (state.viewportLeft != viewportLeft) {
                state.viewportLayer.setTranslationX(viewportLeft);
                state.viewportLeft = viewportLeft;
            }
            if (state.viewportTop != viewportTop) {
                state.viewportLayer.setTranslationY(viewportTop);
                state.viewportTop = viewportTop;
            }

            double scale = ((double) webView.getWidth()) / state.viewportWidthCss;
            int fullWidth = Math.max(
                    2, (int) Math.round(state.widthCss * scale));
            int fullHeight = Math.max(
                    2, (int) Math.round(state.heightCss * scale));

            // Base position inside the clipped viewport at scroll=(0,0).
            state.baseLeft =
                    webLoc[0] - visibleScreen.left +
                    (int) Math.round(state.docLeftCss * scale);
            state.baseTop =
                    webLoc[1] - visibleScreen.top +
                    (int) Math.round(state.docTopCss * scale);

            if (state.videoWidthPx != fullWidth ||
                state.videoHeightPx != fullHeight) {
                ViewGroup.LayoutParams lp = state.videoContainer.getLayoutParams();
                lp.width = fullWidth;
                lp.height = fullHeight;
                state.videoContainer.setLayoutParams(lp);
                state.videoWidthPx = fullWidth;
                state.videoHeightPx = fullHeight;
                applyTextureAspect(state);
            }

            state.geometryValid = true;
            return true;
        } catch (Throwable t) {
            state.geometryValid = false;
            Log.e(TAG, "refresh viewport geometry failed", t);
            return false;
        }
    }

    /**
     * Scroll hot path. No allocation, no global-coordinate query, no relayout,
     * no JavaScript and no MediaPlayer calls.
     */
    private static boolean applyScrollTranslation(WebView webView, State state) {
        if (!state.geometryValid) return false;

        int left = state.baseLeft - webView.getScrollX();
        int top = state.baseTop - webView.getScrollY();

        if (state.lastLeft != left) {
            state.videoContainer.setTranslationX(left);
            state.lastLeft = left;
        }
        if (state.lastTop != top) {
            state.videoContainer.setTranslationY(top);
            state.lastTop = top;
        }

        return left < state.viewportWidth &&
                top < state.viewportHeight &&
                left + state.videoWidthPx > 0 &&
                top + state.videoHeightPx > 0;
    }

    private static void setVideoVisibility(State state, boolean inViewport) {
        state.inViewport = inViewport;
        if (state.videoContainer == null) return;

        int wanted = inViewport ? View.VISIBLE : View.INVISIBLE;
        if (state.videoContainer.getVisibility() != wanted) {
            state.videoContainer.setVisibility(wanted);
        }
    }

    private static void openPlayerIfReady(final State state) {
        if (state == null || state.src == null || state.src.length() == 0) return;
        if (state.surface == null || !state.surface.isValid()) return;

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
                String cookie = CookieManager.getInstance().getCookie(state.src);
                if (cookie != null && cookie.length() > 0) {
                    headers.put("Cookie", cookie);
                }
            } catch (Throwable ignored) {}

            mp.setSurface(state.surface);
            mp.setVolume(0.0f, 0.0f);
            mp.setLooping(state.pendingLoop);
            mp.setDataSource(state.activity, Uri.parse(state.src), headers);

            mp.setOnVideoSizeChangedListener(
                    new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(
                        MediaPlayer player, int width, int height) {
                    state.mediaWidth = width;
                    state.mediaHeight = height;
                    applyTextureAspect(state);
                }
            });

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (state.player != player) return;
                    state.prepared = true;
                    state.mediaWidth = player.getVideoWidth();
                    state.mediaHeight = player.getVideoHeight();
                    applyTextureAspect(state);
                    try { player.setVolume(0.0f, 0.0f); } catch (Throwable ignored) {}
                    try { player.setLooping(state.pendingLoop); } catch (Throwable ignored) {}
                    syncPlayer(state, true);
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    Log.e(TAG,
                            "native texture error what=" + what +
                            " extra=" + extra +
                            " src=" + state.src);
                    return false;
                }
            });

            mp.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "open native texture player failed: " + state.src, t);
            releasePlayer(state);
        }
    }

    private static void syncPlayer(State state, boolean forceSeek) {
        MediaPlayer mp = state.player;
        if (mp == null || !state.prepared) return;

        try {
            long now = SystemClock.uptimeMillis();
            boolean scrolling = now - state.lastScrollMs < 500L;

            if (!scrolling &&
                (forceSeek || now - state.lastDriftSyncMs >= 8000L)) {
                int actual = mp.getCurrentPosition();
                if (forceSeek || Math.abs(actual - state.pendingSeekMs) > 6000) {
                    mp.seekTo(state.pendingSeekMs);
                }
                state.lastDriftSyncMs = now;
            }

            if (!state.inViewport) {
                if (mp.isPlaying()) mp.pause();
            } else if (state.pendingAutoplay) {
                if (!mp.isPlaying()) mp.start();
            } else {
                if (mp.isPlaying()) mp.pause();
            }
        } catch (Throwable ignored) {}
    }

    private static void pauseVisual(State state) {
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
            state.videoWidthPx < 2 ||
            state.videoHeightPx < 2) {
            return;
        }

        int boxW = state.videoWidthPx;
        int boxH = state.videoHeightPx;
        int texW = boxW;
        int texH = boxH;
        int left = 0;
        int top = 0;

        if (state.mediaWidth > 0 && state.mediaHeight > 0) {
            double videoAspect =
                    (double) state.mediaWidth / (double) state.mediaHeight;
            double boxAspect = (double) boxW / (double) boxH;

            if (videoAspect > boxAspect) {
                texW = boxW;
                texH = Math.max(
                        2, (int) Math.round(boxW / videoAspect));
                top = (boxH - texH) / 2;
            } else {
                texH = boxH;
                texW = Math.max(
                        2, (int) Math.round(boxH * videoAspect));
                left = (boxW - texW) / 2;
            }
        }

        ViewGroup.LayoutParams raw = state.texture.getLayoutParams();
        FrameLayout.LayoutParams lp;
        if (raw instanceof FrameLayout.LayoutParams) {
            lp = (FrameLayout.LayoutParams) raw;
        } else {
            lp = new FrameLayout.LayoutParams(texW, texH);
        }

        if (lp.width == texW &&
            lp.height == texH &&
            lp.leftMargin == left &&
            lp.topMargin == top) {
            return;
        }

        lp.width = texW;
        lp.height = texH;
        lp.leftMargin = left;
        lp.topMargin = top;
        state.texture.setLayoutParams(lp);
    }

    /**
     * Cheap eligibility for animation-frame callbacks.
     * No getGlobalVisibleRect/getLocationOnScreen call here.
     */
    private static boolean isCheaplyEligible(WebView webView) {
        try {
            if (webView == null) return false;
            if (webView.getParent() == null) return false;
            if (webView.getWindowToken() == null) return false;
            if (!webView.isShown()) return false;
            if (webView.getVisibility() != View.VISIBLE) return false;
            if (webView.getWindowVisibility() != View.VISIBLE) return false;

            String url = webView.getUrl();
            return url != null && url.contains("etoland.co.kr");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isEligible(WebView webView) {
        try {
            if (!isCheaplyEligible(webView)) return false;
            if (webView.getAlpha() <= 0.01f) return false;
            if (webView.getWidth() < 2 || webView.getHeight() < 2) return false;

            Rect visible = new Rect();
            return webView.getGlobalVisibleRect(visible) &&
                    visible.width() >= 2 &&
                    visible.height() >= 2;
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
            " var r=best.getBoundingClientRect();" +
            " return {" +
            "  src:String(best.currentSrc||best.src||'')," +
            "  page:String(location.href)," +
            "  docLeft:r.left+scrollX,docTop:r.top+scrollY," +
            "  width:r.width,height:r.height," +
            "  viewportWidth:innerWidth,viewportHeight:innerHeight," +
            "  currentTime:Number(best.currentTime||0)," +
            "  paused:!!best.paused,loop:!!best.loop" +
            " };" +
            "}catch(e){return null;}" +
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
        ViewGroup root;

        PassthroughFrameLayout viewportLayer;
        FrameLayout videoContainer;
        TextureView texture;
        Surface surface;
        MediaPlayer player;

        ViewTreeObserver.OnScrollChangedListener scrollListener;
        View.OnLayoutChangeListener layoutListener;
        Runnable positionRunnable;
        Runnable settleRunnable;

        String src = "";
        String pageUrl = "";

        boolean prepared;
        boolean pendingAutoplay;
        boolean pendingLoop;
        boolean visible;
        boolean inViewport;
        boolean positionPosted;
        boolean geometryValid;

        int pendingSeekMs;
        long lastDriftSyncMs;
        long lastScrollMs;

        double docLeftCss;
        double docTopCss;
        double widthCss;
        double heightCss;
        double viewportWidthCss;

        int mediaWidth;
        int mediaHeight;

        int viewportLeft = Integer.MIN_VALUE;
        int viewportTop = Integer.MIN_VALUE;
        int viewportWidth = -1;
        int viewportHeight = -1;

        int baseLeft;
        int baseTop;
        int videoWidthPx = -1;
        int videoHeightPx = -1;

        int lastLeft = Integer.MIN_VALUE;
        int lastTop = Integer.MIN_VALUE;
    }
}
