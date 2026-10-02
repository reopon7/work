package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaPlayer;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.VideoView;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Native Android fallback for Etoland MP4 playback.
 *
 * The site exposes a normal top-level <video> with a direct MP4 URL. Modern
 * Chromium renders that correctly, while the legacy Habit WebView shell can
 * decode/play it but intermittently fails to composite the video surface.
 *
 * This class keeps the site video as the authoritative state/source, but draws
 * the visible video through Android VideoView above the WebView.
 */
final class NativeVideoOverlay {
    private static final String TAG = "HabitNativeVideo";

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

                    // A callback from the old page can arrive after Back/Forward
                    // navigation. Never let stale probe data resurrect an overlay.
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
            if (state.videoView != null && state.videoView.isPlaying()) {
                state.videoView.pause();
            }
        } catch (Throwable ignored) {}

        try {
            if (state.controller != null) {
                state.controller.hide();
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

    static synchronized void destroy(WebView webView) {
        State state = STATES.remove(webView);
        if (state == null) return;
        try {
            if (state.videoView != null) {
                state.videoView.stopPlayback();
            }
        } catch (Throwable ignored) {}
        try {
            if (state.container != null && state.container.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.container.getParent()).removeView(state.container);
            }
        } catch (Throwable ignored) {}
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
            if (activity == null) {
                return;
            }

            State state;
            synchronized (NativeVideoOverlay.class) {
                state = STATES.get(webView);
                if (state == null) {
                    state = createState(activity, webView);
                    if (state == null) return;
                    STATES.put(webView, state);
                }
            }

            final State finalState = state;
            final String pageUrl = o.optString("page", "");
            final double cssLeft = o.optDouble("left", 0.0);
            final double cssTop = o.optDouble("top", 0.0);
            final double cssWidth = o.optDouble("width", 0.0);
            final double cssHeight = o.optDouble("height", 0.0);
            final double viewportWidth = o.optDouble("viewportWidth", 0.0);
            final double currentTime = o.optDouble("currentTime", 0.0);
            final boolean paused = o.optBoolean("paused", true);
            final boolean muted = o.optBoolean("muted", false);
            final boolean loop = o.optBoolean("loop", false);

            if (cssWidth < 2.0 || cssHeight < 2.0 || viewportWidth < 1.0) {
                state.container.setVisibility(View.GONE);
                return;
            }

            positionOverlay(webView, state, cssLeft, cssTop, cssWidth, cssHeight, viewportWidth);

            if (!src.equals(state.src)) {
                state.src = src;
                state.prepared = false;
                state.mediaPlayer = null;
                state.pendingSeekMs = Math.max(0, (int) Math.round(currentTime * 1000.0));
                state.pendingAutoplay = !paused;
                state.pendingMuted = muted;
                state.pendingLoop = loop;

                HashMap<String, String> headers = new HashMap<String, String>();
                if (pageUrl != null && pageUrl.length() > 0) {
                    headers.put("Referer", pageUrl);
                }
                try {
                    String cookie = CookieManager.getInstance().getCookie(src);
                    if (cookie != null && cookie.length() > 0) {
                        headers.put("Cookie", cookie);
                    }
                } catch (Throwable ignored) {}

                try {
                    state.videoView.stopPlayback();
                } catch (Throwable ignored) {}

                if (Build.VERSION.SDK_INT >= 21) {
                    state.videoView.setVideoURI(Uri.parse(src), headers);
                } else {
                    state.videoView.setVideoURI(Uri.parse(src));
                }
                state.videoView.requestFocus();
            } else if (state.prepared) {
                // Keep native playback approximately aligned with the page
                // element after seeks / page-side play-pause changes.
                int desired = Math.max(0, (int) Math.round(currentTime * 1000.0));
                int actual = 0;
                try { actual = state.videoView.getCurrentPosition(); } catch (Throwable ignored) {}
                if (Math.abs(actual - desired) > 1800) {
                    try { state.videoView.seekTo(desired); } catch (Throwable ignored) {}
                }

                try {
                    if (paused && state.videoView.isPlaying()) {
                        state.videoView.pause();
                    } else if (!paused && !state.videoView.isPlaying()) {
                        applyVolume(state, muted);
                        state.videoView.start();
                    }
                } catch (Throwable ignored) {}
            }

            // Navigation/tab switching can happen while MediaPlayer is being
            // prepared. Re-check immediately before showing the Activity-level
            // overlay.
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

        FrameLayout container = new FrameLayout(activity);
        container.setBackgroundColor(0xff000000);
        container.setVisibility(View.GONE);
        container.setClickable(true);

        VideoView video = new VideoView(activity);
        FrameLayout.LayoutParams videoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        container.addView(video, videoLp);

        MediaController controls = new MediaController(activity);
        controls.setAnchorView(video);
        video.setMediaController(controls);

        state.container = container;
        state.videoView = video;
        state.controller = controls;

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                state.mediaPlayer = mp;
                state.prepared = true;
                try { mp.setLooping(state.pendingLoop); } catch (Throwable ignored) {}
                applyVolume(state, state.pendingMuted);
                try {
                    if (state.pendingSeekMs > 0) {
                        state.videoView.seekTo(state.pendingSeekMs);
                    }
                    if (state.pendingAutoplay) {
                        state.videoView.start();
                    }
                } catch (Throwable ignored) {}
            }
        });

        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "native video error what=" + what + " extra=" + extra + " src=" + state.src);
                return false;
            }
        });

        // First touch unmutes and reveals native playback controls.
        video.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    state.userUnmuted = true;
                    applyVolume(state, false);
                    try { state.controller.show(3000); } catch (Throwable ignored) {}
                }
                return false;
            }
        });

        root.addView(container, new ViewGroup.LayoutParams(1, 1));
        return state;
    }

    private static void positionOverlay(
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

        double scale = ((double) webView.getWidth()) / viewportWidth;

        int left = webLoc[0] - rootLoc[0] + (int) Math.round(cssLeft * scale);
        int top = webLoc[1] - rootLoc[1] + (int) Math.round(cssTop * scale);
        int width = Math.max(2, (int) Math.round(cssWidth * scale));
        int height = Math.max(2, (int) Math.round(cssHeight * scale));

        ViewGroup.LayoutParams raw = state.container.getLayoutParams();
        FrameLayout.LayoutParams lp;
        if (raw instanceof FrameLayout.LayoutParams) {
            lp = (FrameLayout.LayoutParams) raw;
        } else {
            lp = new FrameLayout.LayoutParams(width, height);
        }
        lp.width = width;
        lp.height = height;
        lp.leftMargin = left;
        lp.topMargin = top;
        state.container.setLayoutParams(lp);
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

    private static void applyVolume(State state, boolean muted) {
        MediaPlayer mp = state.mediaPlayer;
        if (mp == null) return;
        try {
            float volume = (muted && !state.userUnmuted) ? 0.0f : 1.0f;
            mp.setVolume(volume, volume);
        } catch (Throwable ignored) {}
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
            " try{best.controls=false;best.style.opacity='0.001';best.style.pointerEvents='none';}catch(e){}" +
            " return {" +
            "  src:String(best.currentSrc||best.src||'')," +
            "  page:String(location.href)," +
            "  left:r.left,top:r.top,width:r.width,height:r.height," +
            "  viewportWidth:innerWidth,viewportHeight:innerHeight," +
            "  currentTime:Number(best.currentTime||0)," +
            "  paused:!!best.paused,muted:!!best.muted,loop:!!best.loop" +
            " };" +
            "}catch(e){return null;}" +
            "})();";

    private static final class State {
        Activity activity;
        ViewGroup root;
        FrameLayout container;
        VideoView videoView;
        MediaController controller;
        MediaPlayer mediaPlayer;
        String src = "";
        boolean prepared;
        boolean pendingAutoplay;
        boolean pendingMuted;
        boolean pendingLoop;
        boolean userUnmuted;
        int pendingSeekMs;
    }
}
