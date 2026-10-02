package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * V25 stabilization layer for the V17 Habit Browser baseline.
 *
 * Legacy classes.dex is kept byte-for-byte unchanged. All new behavior is
 * compiled from Java into classes2.dex.
 */
public final class Mod extends App {
    private static final String TAG = "HabitJavaCompat";
    private static final String VIDEO_FALLBACK_ASSET =
            "js/habit_video_canvas_fallback_v25.js";

    private static final Map<WebView, Boolean> STARTED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());

    private static volatile String videoFallbackScript;

    @Override
    public void onCreate() {
        super.onCreate();
        videoFallbackScript = readAsset(VIDEO_FALLBACK_ASSET);
        registerActivityLifecycleCallbacks(new CompatCallbacks());
        Log.i(TAG, "V25 stable compatibility layer initialized");
    }

    private String readAsset(String path) {
        StringBuilder out = new StringBuilder(16384);
        InputStream input = null;
        BufferedReader reader = null;
        try {
            input = getAssets().open(path);
            reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
            }
            return out.toString();
        } catch (Throwable t) {
            Log.e(TAG, "Unable to load " + path, t);
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            try { if (input != null) input.close(); } catch (Throwable ignored) {}
        }
    }

    private static final class CompatCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) { scheduleApply(activity); }
        @Override public void onActivityStarted(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityResumed(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void scheduleApply(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        final View root = activity.getWindow().getDecorView();
        if (root == null) return;

        applyTree(root);
        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 250L);
        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 1200L);
        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 3000L);
    }

    private static void applyTree(View view) {
        if (view == null) return;

        if (view instanceof WebView) {
            applyWebView((WebView) view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i));
            }
        }
    }

    private static void applyWebView(final WebView webView) {
        try {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);

            if (Build.VERSION.SDK_INT >= 17) {
                settings.setMediaPlaybackRequiresUserGesture(false);
            }

            // Preserve the compatibility behavior of the working V17/V24 line.
            if (Build.VERSION.SDK_INT >= 21) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) {
                cookies.setAcceptThirdPartyCookies(webView, true);
            }

            startInjectionLoop(webView);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply WebView compatibility settings", t);
        }
    }

    private static boolean isEtolandUrl(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            return "etoland.co.kr".equals(host) || host.endsWith(".etoland.co.kr");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void startInjectionLoop(final WebView webView) {
        if (Build.VERSION.SDK_INT < 19) return;

        synchronized (STARTED) {
            if (STARTED.containsKey(webView)) return;
            STARTED.put(webView, Boolean.TRUE);
        }

        webView.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // A WebView removed from the view hierarchy should not be kept alive
                    // by this compatibility task.
                    if (webView.getParent() == null && webView.getWindowToken() == null) {
                        synchronized (STARTED) { STARTED.remove(webView); }
                        return;
                    }

                    String url = webView.getUrl();
                    if (isEtolandUrl(url) && videoFallbackScript != null) {
                        webView.evaluateJavascript(
                                "(function(){return !!window.__hbV25Installed;})()",
                                new ValueCallback<String>() {
                                    @Override public void onReceiveValue(String value) {
                                        if (!"true".equals(value)) {
                                            try {
                                                webView.evaluateJavascript(videoFallbackScript, null);
                                            } catch (Throwable t) {
                                                Log.e(TAG, "V25 video fallback injection failed", t);
                                            }
                                        }
                                    }
                                });
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "V25 compatibility loop failed", t);
                }

                try {
                    webView.postDelayed(this, 1500L);
                } catch (Throwable ignored) {
                    synchronized (STARTED) { STARTED.remove(webView); }
                }
            }
        }, 500L);
    }
}
