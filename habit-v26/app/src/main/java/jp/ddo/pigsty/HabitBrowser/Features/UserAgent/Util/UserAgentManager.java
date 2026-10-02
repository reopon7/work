package jp.ddo.pigsty.HabitBrowser.Features.UserAgent.Util;

import android.content.ContentResolver;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jp.ddo.pigsty.HabitBrowser.Component.Application.App;
import jp.ddo.pigsty.HabitBrowser.Features.UserAgent.Model.UserAgentInfo;
import jp.ddo.pigsty.HabitBrowser.Features.UserAgent.Table.TableUserAgent;
import jp.ddo.pigsty.HabitBrowser.Util.SQLite.SQLiteProvider;
import jp.ddo.pigsty.HabitBrowser.Util.Thread.ThreadUtil;

/**
 * Java reconstruction of the V17 UserAgentManager.
 *
 * Persistence workers UserAgentManager$1/$2 remain in the legacy DEX for now.
 * Their required access$000() bridge is preserved explicitly.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class UserAgentManager {
    private static final List userAgentInfoList = new ArrayList();

    public UserAgentManager() {
    }

    static List access$000() {
        return userAgentInfoList;
    }

    public static String getUserAgenet(long id) {
        synchronized (userAgentInfoList) {
            // Legacy code used this monitor as a memory barrier.
        }

        Iterator it = userAgentInfoList.iterator();
        while (it.hasNext()) {
            UserAgentInfo info = (UserAgentInfo) it.next();
            if (info.getId() == id) {
                return info.getUserAgent();
            }
        }
        return null;
    }

    public static List getUserAgentInfoList() {
        synchronized (userAgentInfoList) {
            // Preserve original synchronization point.
        }
        return userAgentInfoList;
    }

    public static String getUserAgentName(long id) {
        synchronized (userAgentInfoList) {
            // Preserve original synchronization point.
        }

        Iterator it = userAgentInfoList.iterator();
        while (it.hasNext()) {
            UserAgentInfo info = (UserAgentInfo) it.next();
            if (info.getId() == id) {
                return info.getName();
            }
        }

        return App.getStrings(0x7f07006e);
    }

    public static synchronized void init() {
        final String key = "system_useragentmanager_init";

        if (App.getPreferenceBoolean(key, true)) {
            App.setPreferenceBoolean(key, false);

            ContentResolver resolver =
                    App.getInstance().getContentResolver();
            Uri uri = TableUserAgent.getUri();

            SQLiteProvider.beginTransaction(
                    resolver,
                    uri,
                    new UserAgentManager$1());
        }

        read();
    }

    public static void read() {
        ThreadUtil.runRowPriority(new UserAgentManager$2());
    }
}
