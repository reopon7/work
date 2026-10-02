package jp.ddo.pigsty.HabitBrowser.Features.Browser.Util;

import android.content.Context;
import android.webkit.CookieSyncManager;

/**
 * Java reconstruction of the V17 CookieUtil.
 *
 * The behavior is intentionally kept identical first. A later modernization
 * pass can replace CookieSyncManager with CookieManager.flush() once this class
 * has been device-validated in Java form.
 */
@SuppressWarnings("deprecation")
public class CookieUtil {
    public CookieUtil() {
    }

    public static void onCreate(Context context) {
        CookieSyncManager.createInstance(context);
    }

    public static void resetSync() {
        CookieSyncManager.getInstance().resetSync();
    }

    public static void startSync() {
        CookieSyncManager.getInstance().startSync();
    }

    public static void stopSync() {
        CookieSyncManager.getInstance().stopSync();
    }

    public static void sync() {
        CookieSyncManager.getInstance().sync();
    }
}
