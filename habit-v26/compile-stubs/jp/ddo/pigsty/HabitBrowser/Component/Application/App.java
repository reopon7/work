package jp.ddo.pigsty.HabitBrowser.Component.Application;
import android.app.Application;
import android.os.Handler;
public class App extends Application {
    public static Handler getHandler() { return null; }
    public static boolean getPreferenceBoolean(String key, boolean defValue) { return defValue; }
    public static void setPreferenceBoolean(String key, boolean value) {}
    public static App getInstance() { return null; }
    public static String getStrings(int resId) { return null; }
}