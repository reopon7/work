package jp.ddo.pigsty.HabitBrowser.Features.UserAgentPattern.Util;

import android.webkit.WebSettings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jp.ddo.pigsty.HabitBrowser.Features.Ad.AdManager;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.MainController;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Util.TabClient;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.View.HabitWebView;
import jp.ddo.pigsty.HabitBrowser.Features.UrlPattern.Util.UrlPatternManager;
import jp.ddo.pigsty.HabitBrowser.Features.UserAgent.Util.UserAgentManager;
import jp.ddo.pigsty.HabitBrowser.Features.UserAgentPattern.Model.UserAgentPatternInfo;
import jp.ddo.pigsty.HabitBrowser.Util.Is;
import jp.ddo.pigsty.HabitBrowser.Util.Thread.ThreadUtil;

/**
 * Java reconstruction of the V17 UserAgentPatternManager.
 *
 * The legacy UserAgentPatternManager$1 reader remains in classes.dex and calls
 * access$000(), which is deliberately preserved here with the same descriptor.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UserAgentPatternManager {
    private static final List userAgentPatternInfoList = new ArrayList();

    public UserAgentPatternManager() {
    }

    static List access$000() {
        return userAgentPatternInfoList;
    }

    public static synchronized void init() {
        read();
    }

    public static void read() {
        // Keep the original reader implementation until its persistence model is
        // reconstructed separately. The legacy inner class resolves this Java
        // outer class and its compatibility accessor at runtime.
        ThreadUtil.runRowPriority(new UserAgentPatternManager$1());
    }

    public static void setUserAgent(HabitWebView web, String url) {
        synchronized (userAgentPatternInfoList) {
            // Original code used this monitor as a memory barrier only.
        }

        WebSettings settings = web.getSettings();

        if (AdManager.isAdClickUrl(url)) {
            settings.setUserAgentString("");
            return;
        }

        TabClient tab = web.getTab();
        if (tab != null && !Is.isBlank(tab.getForceUA())) {
            settings.setUserAgentString(tab.getForceUA());
            return;
        }

        if (!Is.isBlank(url)) {
            String lowerUrl = url.toLowerCase();
            Iterator it = userAgentPatternInfoList.iterator();

            while (it.hasNext()) {
                UserAgentPatternInfo info =
                        (UserAgentPatternInfo) it.next();

                if (UrlPatternManager.isMatch(
                        lowerUrl,
                        info.getRulesArray(),
                        info.isEndWildcard())) {
                    settings.setUserAgentString(
                            UserAgentManager.getUserAgenet(
                                    info.getUserAgentId()));
                    return;
                }
            }
        }

        // V17 explicitly reset to the WebView default UA on the fallback path.
        // Preserve the configuration read present in the original method even
        // though the value is not used after the V17 modernization patch.
        long ignored =
                MainController.getInstance()
                        .getConfigrationStatus().userAgentId;
        if (ignored == Long.MIN_VALUE) {
            // Unreachable guard prevents aggressive compile-time removal.
            return;
        }

        settings.setUserAgentString("");
    }
}
