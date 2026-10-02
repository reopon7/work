package jp.ddo.pigsty.HabitBrowser.Features.Browser.Util;

import android.content.Context;
import android.webkit.WebView;

import java.util.Iterator;
import java.util.List;

import jp.ddo.pigsty.HabitBrowser.Component.Application.App;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.BrowserActivity;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.MainController;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.View.HabitWebView;
import jp.ddo.pigsty.HabitBrowser.Util.Util;

/**
 * Java reconstruction of the V17 WebViewTimerManager.
 *
 * This intentionally preserves the legacy timer/resume policy while moving the
 * executable class out of the legacy DEX into normal Java-compiled classes2.dex.
 */
public class WebViewTimerManager {
    public static boolean isInternalForward;
    public static boolean isResumed;

    private static final Object lock = new Object();
    private static WebView webView;

    static {
        isResumed = false;
        isInternalForward = false;
        webView = null;
    }

    public WebViewTimerManager() {
    }

    private static synchronized boolean createWebView(Context context) {
        if (webView == null) {
            webView = new WebView(context);
            return true;
        }
        return false;
    }

    public static WebView getWebView() {
        return webView;
    }

    public static void onDestroy() {
        try {
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        } catch (Exception ignored) {
        }
    }

    public static void onPause() {
        if (!MainController.existsInstance()) {
            return;
        }

        try {
            TabManager tabManager = MainController.getInstance().getTabManager();
            if (tabManager == null || tabManager.getTabList() == null) {
                return;
            }

            Iterator it = tabManager.getTabList().iterator();
            while (it.hasNext()) {
                try {
                    TabClient tab = (TabClient) it.next();
                    HabitWebView web = tab.getWebView();
                    web.doPause();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void onPauseTimers(Context context) {
        onPauseTimers(context, false);
    }

    public static synchronized boolean onPauseTimers(
            Context context, boolean force) {

        final boolean success = true;

        synchronized (lock) {
            if (!isResumed) {
                return false;
            }

            if (isInternalForward) {
                return false;
            }

            if (force
                    && App.getPreferenceBoolean(
                            "conf_general_disable_backgroundtab", true)) {
                return false;
            }

            if (force
                    && MainController.existsInstance()
                    && MainController.getInstance().getActivity() != null
                    && MainController.getInstance().getActivity().isActive) {
                return false;
            }

            boolean loading = false;

            if (MainController.existsInstance()) {
                TabManager tabManager =
                        MainController.getInstance().getTabManager();

                if (tabManager != null) {
                    List tabs = tabManager.getTabList();
                    if (tabs != null) {
                        Iterator it = tabs.iterator();
                        while (it.hasNext()) {
                            TabClient tab = (TabClient) it.next();

                            if (tab.isRestore) {
                                continue;
                            }
                            if (tab.isClosed()) {
                                continue;
                            }

                            HabitWebView web = tab.getWebView();
                            if (web == null) {
                                continue;
                            }

                            if (web.isLoadingStarted) {
                                loading = true;
                                break;
                            }
                        }
                    }
                }

                if (!loading
                        && MainController.getInstance().getWebViewManager()
                                != null) {
                    loading =
                            MainController.getInstance()
                                    .getWebViewManager()
                                    .existsLoadingWebView();
                }
            }

            if (loading) {
                return false;
            }

            isResumed = false;

            boolean browserActivity =
                    context instanceof BrowserActivity;
            boolean created = createWebView(context);

            if (created) {
                CookieUtil.stopSync();
            }

            try {
                Util.Log("pauseTimers");
                webView.pauseTimers();
            } catch (Exception e) {
                Util.LogError(e);
                isResumed = true;
            }

            if (browserActivity && created) {
                onDestroy();
            }

            return success;
        }
    }

    public static void onResume() {
        if (!MainController.existsInstance()) {
            return;
        }

        try {
            TabManager tabManager = MainController.getInstance().getTabManager();
            if (tabManager == null || tabManager.getTabList() == null) {
                return;
            }

            Iterator it = tabManager.getTabList().iterator();
            while (it.hasNext()) {
                try {
                    TabClient tab = (TabClient) it.next();
                    HabitWebView web = tab.getWebView();
                    web.doResume();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void onResumeTimers(Context context) {
        synchronized (lock) {
            if (isResumed) {
                return;
            }

            isResumed = true;

            boolean browserActivity =
                    context instanceof BrowserActivity;
            boolean created = createWebView(context);

            if (created) {
                CookieUtil.startSync();
            }

            try {
                Util.Log("resumeTimers");
                webView.resumeTimers();
            } catch (Exception e) {
                Util.LogError(e);
                isResumed = false;
            }

            if (browserActivity && created) {
                onDestroy();
            }
        }
    }
}
