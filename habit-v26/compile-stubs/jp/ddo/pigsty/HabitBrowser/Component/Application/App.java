package jp.ddo.pigsty.HabitBrowser.Component.Application;
import android.app.Application;
import android.os.Handler;
public class App extends Application {
    public static Handler getHandler() { return null; }
    public static boolean getPreferenceBoolean(String key, boolean defValue) { return defValue; }
}