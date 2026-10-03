package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
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
import android.widget.AbsoluteLayout;
import android.widget.FrameLayout;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * V44 Etoland native video fallback.
 *
 * V41-V43 placed the TextureView at Activity-root level and manually chased
 * WebView scrolling. That was much better than VideoView/SurfaceView, but it
 * could still feel detached because two unrelated view trees had to be kept in
 * sync.
 *
 * V44 puts the TextureView container directly inside the WebView itself.
 * WebView scroll/clip now moves the video with the document natively, so there
 * is no scroll listener, no per-frame translation, no global-coordinate query,
 * and no clipBounds churn.
 */
@SuppressWarnings("deprecation")
final class NativeVideoOverlay {
    private static final String TAG = "HabitNativeInWeb";

    private static final WeakHashMap<WebView, State> STATES =
            new WeakHashMap<WebView, State>();

    private NativeVideoOverlay() {}

    static synchronized boolean hasOverlay(WebView webView) {
        State state = STATES.get(webView);
        return state != null && state.src != null && state.src.length() > 0;
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

        releasePlayer(state);

        try {
            if (state.surface != null) {
                state.surface.release();
                state.surface = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (state.container != null &&
                state.container.getParent() instanceof ViewGroup) {
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

            state.pageUrl = o.optString("page", "");
            state.docLeftCss = o.optDouble("docLeft", 0.0);
            state.docTopCss = o.optDouble("docTop", 0.0);
            state.widthCss = o.optDouble("width", 0.0);
            state.heightCss = o.optDouble("height", 0.0);
            state.viewportWidthCss = o.optDouble("viewportWidth", 0.0);
            state.pendingSeekMs =
                    Math.max(0, (int) Math.round(o.optDouble("currentTime", 0.0) * 1000.0));
            state.pendingAutoplay = !o.optBoolean("paused", true);
            state.pendingLoop = o.optBoolean("loop", false);

            if (state.widthCss < 2.0 ||
                state.heightCss < 2.0 ||
                state.viewportWidthCss < 1.0) {
                hide(webView);
                return;
            }

            if (!ensureAttached(webView, state) ||
                !updateChildLayout(webView, state)) {
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

            if (!isEligible(webView)) {
                destroy(webView);
                return;
            }

            showVisual(state);
        } catch (Throwable t) {
            Log.e(TAG, "apply probe failed: " + value, t);
        }
    }

    private static State createState(Activity activity, final WebView webView) {
        final State state = new State();
        state.activity = activity;
        state.webView = webView;

        PassthroughFrameLayout container = new PassthroughFrameLayout(activity);
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

        try {
            webView.addView(
                    container,
                    new AbsoluteLayout.LayoutParams(1, 1, 0, 0));
        } catch (Throwable t) {
            Log.e(TAG, "failed to attach in-WebView video child", t);
            return null;
        }

        return state;
    }

    private static boolean ensureAttached(WebView webView, State state) {
        try {
            if (state.container == null) return false;
            if (state.container.getParent() == webView) return true;

            if (state.container.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.container.getParent()).removeView(state.container);
            }

            webView.addView(
                    state.container,
                    new AbsoluteLayout.LayoutParams(
                            Math.max(1, state.layoutWidth),
                            Math.max(1, state.layoutHeight),
                            state.layoutX == Integer.MIN_VALUE ? 0 : state.layoutX,
                            state.layoutY == Integer.MIN_VALUE ? 0 : state.layoutY));

            return true;
        } catch (Throwable t) {
            Log.e(TAG, "failed to reattach in-WebView video child", t);
            return false;
        }
    }

    /**
     * Child coordinates are document coordinates in physical WebView pixels.
     * Because the container is a real WebView child, WebView's own scroll and
     * clipping pipeline moves it with the page automatically.
     */
    private static boolean updateChildLayout(WebView webView, State state) {
        try {
            int webWidth = webView.getWidth();
            if (webWidth < 2 || state.viewportWidthCss < 1.0) return false;

            double scale = ((double) webWidth) / state.viewportWidthCss;

            int x = (int) Math.round(state.docLeftCss * scale);
            int y = (int) Math.round(state.docTopCss * scale);
            int width = Math.max(2, (int) Math.round(state.widthCss * scale));
            int height = Math.max(2, (int) Math.round(state.heightCss * scale));

            boolean changed =
                    x != state.layoutX ||
                    y != state.layoutY ||
                    width != state.layoutWidth ||
                    height != state.layoutHeight;

            if (changed) {
                AbsoluteLayout.LayoutParams lp;
                ViewGroup.LayoutParams raw = state.container.getLayoutParams();

                if (raw instanceof AbsoluteLayout.LayoutParams) {
                    lp = (AbsoluteLayout.LayoutParams) raw;
                    lp.x = x;
                    lp.y = y;
                    lp.width = width;
                    lp.height = height;
                } else {
                    lp = new AbsoluteLayout.LayoutParams(width, height, x, y);
                }

                state.container.setLayoutParams(lp);
                state.layoutX = x;
                state.layoutY = y;
                state.layoutWidth = width;
                state.layoutHeight = height;

                applyTextureAspect(state);
            }

            return true;
        } catch (Throwable t) {
            Log.e(TAG, "update child layout failed", t);
            return false;
        }
    }

    private static void showVisual(State state) {
        if (state == null || state.container == null) return;

        if (!state.visible) {
            state.container.setVisibility(View.VISIBLE);
            state.container.bringToFront();
            state.visible = true;
        }

        if (state.prepared && state.pendingAutoplay) {
            try {
                if (!state.player.isPlaying()) state.player.start();
            } catch (Throwable ignored) {}
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

            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
                    state.videoWidth = width;
                    state.videoHeight = height;
                    applyTextureAspect(state);
                }
            });

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    if (state.player != player) return;

                    state.prepared = true;
                    state.videoWidth = player.getVideoWidth();
                    state.videoHeight = player.getVideoHeight();

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

            if (forceSeek || now - state.lastDriftSyncMs >= 7000L) {
                int actual = mp.getCurrentPosition();

                if (forceSeek || Math.abs(actual - state.pendingSeekMs) > 5000) {
                    mp.seekTo(state.pendingSeekMs);
                }

                state.lastDriftSyncMs = now;
            }

            if (state.pendingAutoplay) {
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
                texH = Math.max(2, (int) Math.round(boxW / videoAspect));
                top = (boxH - texH) / 2;
            } else {
                texH = boxH;
                texW = Math.max(2, (int) Math.round(boxH * videoAspect));
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
            " var r=best.getBoundingClientRect();" +
            " return {" +
            "  src:String(best.currentSrc||best.src||'')," +
            "  page:String(location.href)," +
            "  docLeft:r.left+scrollX,docTop:r.top+scrollY," +
            "  width:r.width,height:r.height," +
            "  viewportWidth:innerWidth," +
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
        PassthroughFrameLayout container;
        TextureView texture;
        Surface surface;
        MediaPlayer player;

        String src = "";
        String pageUrl = "";

        boolean prepared;
        boolean pendingAutoplay;
        boolean pendingLoop;
        boolean visible;

        int pendingSeekMs;
        long lastDriftSyncMs;

        double docLeftCss;
        double docTopCss;
        double widthCss;
        double heightCss;
        double viewportWidthCss;

        int videoWidth;
        int videoHeight;

        int layoutX = Integer.MIN_VALUE;
        int layoutY = Integer.MIN_VALUE;
        int layoutWidth = -1;
        int layoutHeight = -1;
    }
}
