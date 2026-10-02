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
import android.widget.FrameLayout;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Passive native video visualizer for Etoland.
 *
 * V38/V39 used VideoView (SurfaceView-backed on many devices) at Activity root.
 * That solved the gray WebView video, but the SurfaceView could appear detached
 * from the page and consumed touch/scroll input. V41 replaces it with a normal
 * TextureView-backed MediaPlayer overlay that:
 *
 * - is clipped to the visible WebView rectangle
 * - follows the page video's getBoundingClientRect()
 * - never consumes touch, so scroll/back/page gestures reach WebView
 * - stays muted; original HTML5 video remains the audio/control authority
 */
final class NativeVideoOverlay {
    private static final String TAG = "HabitNativeTexture";

    private static final WeakHashMap<WebView, State> STATES =
            new WeakHashMap<WebView, State>();

    private NativeVideoOverlay() {}

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

        try {
            if (state.player != null && state.prepared && state.player.isPlaying()) {
                state.player.pause();
            }
        } catch (Throwable ignored) {}

        if (state.container != null) {
            state.container.setVisibility(View.GONE);
        }
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

        releasePlayer(state);

        try {
            if (state.container != null && state.container.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.container.getParent()).removeView(state.container);
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

            final String pageUrl = o.optString("page", "");
            final double cssLeft = o.optDouble("left", 0.0);
            final double cssTop = o.optDouble("top", 0.0);
            final double cssWidth = o.optDouble("width", 0.0);
            final double cssHeight = o.optDouble("height", 0.0);
            final double viewportWidth = o.optDouble("viewportWidth", 0.0);
            final double currentTime = o.optDouble("currentTime", 0.0);
            final boolean paused = o.optBoolean("paused", true);
            final boolean loop = o.optBoolean("loop", false);

            if (cssWidth < 2.0 || cssHeight < 2.0 || viewportWidth < 1.0) {
                hide(webView);
                return;
            }

            if (!positionOverlay(
                    webView, state, cssLeft, cssTop, cssWidth, cssHeight, viewportWidth)) {
                hide(webView);
                return;
            }

            state.pendingSeekMs = Math.max(0, (int) Math.round(currentTime * 1000.0));
            state.pendingAutoplay = !paused;
            state.pendingLoop = loop;

            if (!src.equals(state.src)) {
                state.src = src;
                state.pageUrl = pageUrl;
                state.prepared = false;
                openPlayerIfReady(state);
            } else if (state.prepared && state.player != null) {
                syncPlayer(state);
            }

            if (!isEligible(webView)) {
                destroy(webView);
                return;
            }

            state.container.setVisibility(View.VISIBLE);
            state.container.bringToFront();
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
        state.root = root;

        PassthroughFrameLayout container = new PassthroughFrameLayout(activity);
        container.setBackgroundColor(0xff000000);
        container.setVisibility(View.GONE);
        container.setClipChildren(true);
        container.setClipToPadding(true);

        final TextureView texture = new TextureView(activity);
        texture.setOpaque(true);
        texture.setClickable(false);
        texture.setFocusable(false);

        container.addView(texture, new FrameLayout.LayoutParams(1, 1));

        state.container = container;
        state.texture = texture;

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture surface, int width, int height) {
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

        root.addView(container, new ViewGroup.LayoutParams(1, 1));
        return state;
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

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (state.player != player) return;
                    state.prepared = true;
                    try { player.setVolume(0.0f, 0.0f); } catch (Throwable ignored) {}
                    try { player.setLooping(state.pendingLoop); } catch (Throwable ignored) {}
                    syncPlayer(state);
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

    private static void syncPlayer(State state) {
        MediaPlayer mp = state.player;
        if (mp == null || !state.prepared) return;

        try {
            mp.setVolume(0.0f, 0.0f);
            mp.setLooping(state.pendingLoop);

            int actual = mp.getCurrentPosition();
            if (Math.abs(actual - state.pendingSeekMs) > 1200) {
                mp.seekTo(state.pendingSeekMs);
            }

            if (state.pendingAutoplay) {
                if (!mp.isPlaying()) mp.start();
            } else {
                if (mp.isPlaying()) mp.pause();
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

    /**
     * Position in Activity-root coordinates, but clip to the WebView's real
     * visible rectangle. The inner TextureView keeps full video dimensions so
     * clipping the container behaves like the video is genuinely inside the
     * scrolling page rather than floating above the browser chrome.
     */
    private static boolean positionOverlay(
            WebView webView,
            State state,
            double cssLeft,
            double cssTop,
            double cssWidth,
            double cssHeight,
            double viewportWidth) {

        int[] webLoc = new int[2];
        int[] rootLoc = new int[2];
        webView.getLocationOnScreen(webLoc);
        state.root.getLocationOnScreen(rootLoc);

        Rect visibleScreen = new Rect();
        if (!webView.getGlobalVisibleRect(visibleScreen)) return false;

        double scale = ((double) webView.getWidth()) / viewportWidth;

        int fullLeft = webLoc[0] - rootLoc[0] + (int) Math.round(cssLeft * scale);
        int fullTop = webLoc[1] - rootLoc[1] + (int) Math.round(cssTop * scale);
        int fullWidth = Math.max(2, (int) Math.round(cssWidth * scale));
        int fullHeight = Math.max(2, (int) Math.round(cssHeight * scale));

        int visibleLeft = visibleScreen.left - rootLoc[0];
        int visibleTop = visibleScreen.top - rootLoc[1];
        int visibleRight = visibleScreen.right - rootLoc[0];
        int visibleBottom = visibleScreen.bottom - rootLoc[1];

        int clipLeft = Math.max(fullLeft, visibleLeft);
        int clipTop = Math.max(fullTop, visibleTop);
        int clipRight = Math.min(fullLeft + fullWidth, visibleRight);
        int clipBottom = Math.min(fullTop + fullHeight, visibleBottom);

        int clipWidth = clipRight - clipLeft;
        int clipHeight = clipBottom - clipTop;
        if (clipWidth < 2 || clipHeight < 2) return false;

        ViewGroup.LayoutParams raw = state.container.getLayoutParams();
        FrameLayout.LayoutParams outer;
        if (raw instanceof FrameLayout.LayoutParams) {
            outer = (FrameLayout.LayoutParams) raw;
        } else {
            outer = new FrameLayout.LayoutParams(clipWidth, clipHeight);
        }
        outer.width = clipWidth;
        outer.height = clipHeight;
        outer.leftMargin = clipLeft;
        outer.topMargin = clipTop;
        state.container.setLayoutParams(outer);

        ViewGroup.LayoutParams textureRaw = state.texture.getLayoutParams();
        FrameLayout.LayoutParams inner;
        if (textureRaw instanceof FrameLayout.LayoutParams) {
            inner = (FrameLayout.LayoutParams) textureRaw;
        } else {
            inner = new FrameLayout.LayoutParams(fullWidth, fullHeight);
        }
        inner.width = fullWidth;
        inner.height = fullHeight;
        inner.leftMargin = fullLeft - clipLeft;
        inner.topMargin = fullTop - clipTop;
        state.texture.setLayoutParams(inner);

        return true;
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
            if (url == null || !url.contains("etoland.co.kr")) return false;

            Rect visible = new Rect();
            if (!webView.getGlobalVisibleRect(visible)) return false;
            if (visible.width() < 2 || visible.height() < 2) return false;

            return true;
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
            "  left:r.left,top:r.top,width:r.width,height:r.height," +
            "  viewportWidth:innerWidth,viewportHeight:innerHeight," +
            "  currentTime:Number(best.currentTime||0)," +
            "  paused:!!best.paused,loop:!!best.loop" +
            " };" +
            "}catch(e){return null;}" +
            "})();";

    /**
     * Never claims the pointer stream. Returning false here allows the Activity
     * root to continue hit-testing the underlying WebView, so page scrolling
     * and gestures behave exactly as if the native visual layer did not exist.
     */
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
        ViewGroup root;
        PassthroughFrameLayout container;
        TextureView texture;
        Surface surface;
        MediaPlayer player;
        String src = "";
        String pageUrl = "";
        boolean prepared;
        boolean pendingAutoplay;
        boolean pendingLoop;
        int pendingSeekMs;
    }
}
