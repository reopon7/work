package jp.ddo.pigsty.HabitBrowser.Features.Browser.Util;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import jp.ddo.pigsty.HabitBrowser.Component.Application.App;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.MainController;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Model.ConfigrationStatus;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Model.WebViewData;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.View.HabitWebView;
import jp.ddo.pigsty.HabitBrowser.Features.SettingPattern.Util.SettingPatternManager;
import jp.ddo.pigsty.HabitBrowser.Features.UserAgentPattern.Util.UserAgentPatternManager;
import jp.ddo.pigsty.HabitBrowser.Util.IO.IOUtil;
import jp.ddo.pigsty.HabitBrowser.Util.Is;
import jp.ddo.pigsty.HabitBrowser.Util.Manager.IManager;
import jp.ddo.pigsty.HabitBrowser.Util.UI.UIUtil;
import jp.ddo.pigsty.HabitBrowser.Util.Util;

/**
 * Java reconstruction of the legacy V17 WebViewManager.
 *
 * Source reconstructed from the V17 Dalvik executable listing.
 * The public API, field descriptors and behavior are intentionally kept
 * compatible with the original class so callers in the untouched legacy DEX
 * can resolve this Java-compiled replacement.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class WebViewManager implements IManager {
    public static double CHROMIUM_VERSION;

    private static final boolean isUseDb = false;
    private static final int minimumCount;

    private final HashMap cacheMap;
    public final Queue cacheQueue;
    private final HashMap webViewDataMap;

    static {
        minimumCount = Build.VERSION.SDK_INT >= 19 ? 3 : 2;
        CHROMIUM_VERSION = 0.0d;
    }

    public WebViewManager() {
        cacheMap = new HashMap();
        cacheQueue = new LinkedList();
        webViewDataMap = new HashMap();
    }

    private void removeWebView(HabitWebView web, boolean close, boolean saveData) {
        Long tabId = Long.valueOf(web.getTabId());

        if (cacheMap.containsKey(tabId)) {
            LinkedList list = (LinkedList) cacheMap.get(tabId);
            if (list != null) {
                list.remove(web);
            }
        }
        cacheQueue.remove(web);

        if (saveData) {
            WebViewData data = createWebViewData(web);
            if (data != null) {
                ArrayList list;
                if (webViewDataMap.containsKey(tabId)) {
                    list = (ArrayList) webViewDataMap.get(tabId);
                } else {
                    list = new ArrayList();
                    webViewDataMap.put(tabId, list);
                }
                list.add(data);
            }
        }

        if (close) {
            web.close();
        } else {
            UIUtil.leaveView(web);
        }
    }

    public void addWebView(long tabId, HabitWebView web) {
        Long key = Long.valueOf(tabId);
        if (!cacheMap.containsKey(key)) {
            cacheMap.put(key, new LinkedList());
        }
        ((LinkedList) cacheMap.get(key)).add(web);
        cacheQueue.add(web);
    }

    public void addWebViewDataList(List list) {
        if (list == null || list.isEmpty()) {
            return;
        }

        WebViewData first = (WebViewData) list.get(0);
        Long key = Long.valueOf(first.getTabId());

        ArrayList target;
        if (webViewDataMap.containsKey(key)) {
            target = (ArrayList) webViewDataMap.get(key);
        } else {
            target = new ArrayList();
            webViewDataMap.put(key, target);
        }
        target.addAll(list);
    }

    public void applySettings() {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                ((HabitWebView) it.next()).applySettings();
            }
        } catch (Exception e) {
            Util.LogError(e);
        }
    }

    public synchronized void checkMemory() {
        if (Build.VERSION.SDK_INT >= 19) {
            return;
        }

        try {
            Runtime runtime = Runtime.getRuntime();
            double free = (double) runtime.freeMemory();
            double threshold = ((double) runtime.totalMemory()) * 0.05d;
            if (free > threshold) {
                return;
            }

            int removeCount = cacheQueue.size() - minimumCount;
            for (int i = 0; i < removeCount; i++) {
                HabitWebView web = (HabitWebView) cacheQueue.poll();
                if (web != null) {
                    removeWebView(web, true, true);
                }
            }
        } catch (Exception ignored) {
            // Legacy implementation intentionally swallowed memory cleanup errors.
        }
    }

    public synchronized void cleanMemory() {
        ConfigrationStatus status =
                MainController.getInstance().getConfigrationStatus();

        int allCount = status.fastBackCacheCountAll;
        int tabCount = status.fastBackCacheCountTab;

        if (allCount <= 0 && tabCount <= 0) {
            return;
        }

        if (tabCount > 0) {
            TabClient currentTab =
                    MainController.getInstance().getTabManager().getNowTab();

            if (currentTab != null) {
                Long tabKey = Long.valueOf(currentTab.getTabId());
                if (cacheMap.containsKey(tabKey)) {
                    Queue tabQueue = (Queue) cacheMap.get(tabKey);
                    while (tabQueue != null && tabQueue.size() > tabCount) {
                        HabitWebView web = (HabitWebView) tabQueue.poll();
                        if (web != null) {
                            removeWebView(web, true, true);
                        }
                    }
                }
            }
        }

        if (allCount > 0) {
            while (cacheQueue.size() > allCount) {
                HabitWebView web = (HabitWebView) cacheQueue.poll();
                if (web != null) {
                    removeWebView(web, true, true);
                }
            }
        }
    }

    public void clearCache() {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                ((HabitWebView) it.next()).clearCache(true);
            }
        } catch (Exception ignored) {
        }
    }

    public void clearFormdata() {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                ((HabitWebView) it.next()).clearFormData();
            }
        } catch (Exception ignored) {
        }
    }

    public void clearHistory() {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                HabitWebView web = (HabitWebView) it.next();
                web.clearHistory();
                web.clearMatches();
            }
        } catch (Exception ignored) {
        }
    }

    public void clearNextWebView(long tabId, long webViewId) {
        Long key = Long.valueOf(tabId);

        if (cacheMap.containsKey(key)) {
            LinkedList list = (LinkedList) cacheMap.get(key);
            if (list != null) {
                Iterator it = list.iterator();
                while (it.hasNext()) {
                    HabitWebView web = (HabitWebView) it.next();
                    if (web.getWebViewId() > webViewId) {
                        it.remove();
                        cacheQueue.remove(web);
                        web.close();
                    }
                }
            }
        }

        ArrayList dataList = (ArrayList) webViewDataMap.get(key);
        if (dataList != null) {
            Iterator it = dataList.iterator();
            while (it.hasNext()) {
                WebViewData data = (WebViewData) it.next();
                if (data.getWebViewId() > webViewId) {
                    it.remove();
                }
            }
        }
    }

    public HabitWebView createWebView(
            Context context, long tabId, long webViewId) {
        return createWebView(context, tabId, webViewId, null);
    }

    public HabitWebView createWebView(
            Context context,
            long tabId,
            long webViewId,
            HabitWebView suppliedWebView) {

        HabitWebView web = suppliedWebView;

        if (web == null) {
            web = new HabitWebView(context);
            if (CHROMIUM_VERSION == 0.0d) {
                updateChromiumVersion(web);
            }
        }

        TabClient tab =
                MainController.getInstance().getTabManager().getTab(tabId);
        web.init(tab);
        web.setWebViewId(webViewId);

        WebViewData saved = null;
        ArrayList list =
                (ArrayList) webViewDataMap.get(Long.valueOf(tabId));

        if (list != null) {
            Iterator it = list.iterator();
            while (it.hasNext()) {
                WebViewData item = (WebViewData) it.next();
                if (item.getWebViewId() == webViewId) {
                    saved = item;
                    it.remove();
                    break;
                }
            }
        }

        if (saved != null) {
            web.setWebViewId(saved.getWebViewId());

            String restoreUrl = null;
            ArrayList urls = saved.getUrlList();
            if (urls != null && urls.size() > saved.getIndex()) {
                restoreUrl = (String) urls.get(saved.getIndex());
                UserAgentPatternManager.setUserAgent(web, restoreUrl);
                web.setSettingPatternInfo(
                        SettingPatternManager.getSettingPattern(restoreUrl));
            }

            try {
                Bundle state =
                        IOUtil.byteArrayToBundle(saved.getWebViewBundle());
                if (state.isEmpty()) {
                    throw new Exception();
                }
                web.restoreState(state);
            } catch (Exception ignored) {
                if (!Is.isBlank(restoreUrl)) {
                    web.loadUrl(restoreUrl);
                }
            }
        }

        if (MainController.getInstance()
                .getConfigrationStatus().isCheckMemory) {
            checkMemory();
        }

        return web;
    }

    private static void updateChromiumVersion(HabitWebView web) {
        String ua = web.getSettings().getUserAgentString();
        if (ua == null || ua.isEmpty()) {
            CHROMIUM_VERSION = 1.0d;
            return;
        }

        try {
            int chrome = ua.indexOf("Chrome/");
            int end = ua.indexOf(" ", chrome + 1);
            String version = ua.substring(chrome + 7, end);
            int dot = version.indexOf('.');
            CHROMIUM_VERSION =
                    Double.parseDouble(version.substring(0, dot));
        } catch (Exception ignored) {
            CHROMIUM_VERSION = 1.0d;
        }
    }

    public WebViewData createWebViewData(HabitWebView web) {
        Bundle bundle = new Bundle();
        WebBackForwardList history = web.saveState(bundle);

        WebViewData data = new WebViewData();
        data.setWebViewId(web.getWebViewId());
        data.setTabId(web.getTabId());

        if (history != null) {
            data.setIndex(history.getCurrentIndex());
            data.setSize(history.getSize());
        } else {
            data.setIndex(0);
            data.setSize(0);
        }

        data.setWebViewBundle(IOUtil.bundleToByteArray(bundle));

        if (history == null) {
            data.getUrlList().add(web.getPageUrl());
            data.getTitleList().add(web.getPageTitle());
            return data;
        }

        int size = history.getSize();
        for (int i = 0; i < size; i++) {
            WebHistoryItem item = history.getItemAtIndex(i);
            data.getUrlList().add(item.getUrl());
            data.getTitleList().add(item.getTitle());
        }

        return data;
    }

    @Override
    public void destroy() {
        try {
            Collection lists = cacheMap.values();
            Iterator listIt = lists.iterator();

            while (listIt.hasNext()) {
                List list = (List) listIt.next();
                Iterator webIt = list.iterator();
                while (webIt.hasNext()) {
                    try {
                        ((HabitWebView) webIt.next()).close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            cacheMap.clear();
            cacheQueue.clear();
            webViewDataMap.clear();
        } catch (Exception ignored) {
        }
    }

    public boolean existsLoadingWebView() {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                HabitWebView web = (HabitWebView) it.next();
                if (web.isLoadingStarted) {
                    return true;
                }
            }
        } catch (Exception e) {
            Util.LogError(e);
        }
        return false;
    }

    public HabitWebView getWebView(
            Context context, long tabId, long webViewId) {
        Long key = Long.valueOf(tabId);

        if (!cacheMap.containsKey(key)) {
            return createWebView(context, tabId, webViewId);
        }

        LinkedList list = (LinkedList) cacheMap.get(key);
        if (list != null) {
            Iterator it = list.iterator();
            while (it.hasNext()) {
                HabitWebView web = (HabitWebView) it.next();
                if (web.getWebViewId() == webViewId) {
                    removeWebView(web, false, false);
                    return web;
                }
            }
        }

        return createWebView(context, tabId, webViewId);
    }

    public WebViewData getWebViewData(long tabId, long webViewId) {
        WebViewData result = null;

        try {
            ArrayList saved =
                    (ArrayList) webViewDataMap.get(Long.valueOf(tabId));

            if (saved != null) {
                Iterator it = saved.iterator();
                while (it.hasNext()) {
                    WebViewData item = (WebViewData) it.next();
                    if (item.getWebViewId() == webViewId) {
                        result = item;
                        break;
                    }
                }
            }

            if (result != null) {
                return result;
            }

            Long key = Long.valueOf(tabId);
            if (cacheMap.containsKey(key)) {
                List list = (List) cacheMap.get(key);
                for (int i = 0; i < list.size(); i++) {
                    HabitWebView web = (HabitWebView) list.get(i);
                    if (web.getWebViewId() == webViewId) {
                        return createWebViewData(web);
                    }
                }
            }
        } catch (Exception e) {
            Util.LogError(e);
        }

        return result;
    }

    public synchronized void notifyLowMemory() {
        try {
            if (cacheQueue.size() > minimumCount) {
                HabitWebView web =
                        (HabitWebView) cacheQueue.poll();
                if (web != null) {
                    removeWebView(web, true, true);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void removeTab(long tabId) {
        List list =
                (List) cacheMap.remove(Long.valueOf(tabId));

        if (list != null) {
            Iterator it = list.iterator();
            while (it.hasNext()) {
                final HabitWebView web =
                        (HabitWebView) it.next();

                App.getHandler().post(new Runnable() {
                    @Override public void run() {
                        removeWebView(web, true, false);
                    }
                });
            }
        }

        webViewDataMap.remove(Long.valueOf(tabId));
    }

    public void setNetworkSettings(boolean enabled) {
        try {
            Iterator it = cacheQueue.iterator();
            while (it.hasNext()) {
                ((HabitWebView) it.next()).setNetwrok(enabled);
            }
        } catch (Exception ignored) {
        }
    }
}
