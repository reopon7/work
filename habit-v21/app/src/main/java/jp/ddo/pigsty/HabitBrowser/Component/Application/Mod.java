package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Java-compiled modernization layer for the V17 Habit Browser baseline.
 *
 * This class deliberately does not replace Habit UI, tabs, gestures, Quick Menu,
 * settings import, navigation, or the legacy WebViewClient/WebChromeClient.
 * It only applies modern WebView media/cookie settings to every WebView that is
 * already created by the legacy app.
 */
public final class Mod extends App {
    private static final String TAG = "HabitJavaCompat";

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new CompatCallbacks());
        Log.i(TAG, "Java compatibility layer initialized");
    }

    private static final class CompatCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            scheduleApply(activity);
        }
        @Override public void onActivityStarted(Activity activity) {
            scheduleApply(activity);
        }
        @Override public void onActivityResumed(Activity activity) {
            scheduleApply(activity);
        }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void scheduleApply(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }

        final View root = activity.getWindow().getDecorView();
        if (root == null) {
            return;
        }

        applyTree(root);

        root.postDelayed(new Runnable() {
            @Override public void run() {
                applyTree(root);
            }
        }, 250L);

        root.postDelayed(new Runnable() {
            @Override public void run() {
                applyTree(root);
            }
        }, 1200L);
    }

    private static void applyTree(View view) {
        if (view == null) {
            return;
        }

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

    private static void applyWebView(WebView webView) {
        try {
            WebSettings settings = webView.getSettings();

            // Preserve ordinary modern-web requirements.
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);

            // Critical media compatibility setting. The old Habit Browser did
            // not explicitly disable this gate.
            if (Build.VERSION.SDK_INT >= 17) {
                settings.setMediaPlaybackRequiresUserGesture(false);
            }

            // V17/V10 already behaved permissively for mixed content. Keep the
            // same compatibility policy instead of tightening it mid-migration.
            if (Build.VERSION.SDK_INT >= 21) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) {
                cookies.setAcceptThirdPartyCookies(webView, true);
            }

            Log.d(TAG, "Applied WebView compatibility settings: " + webView.getUrl());
        } catch (Throwable t) {
            // Compatibility code must never take down the legacy browser.
            Log.e(TAG, "Failed to apply WebView compatibility settings", t);
        }
    }
}
