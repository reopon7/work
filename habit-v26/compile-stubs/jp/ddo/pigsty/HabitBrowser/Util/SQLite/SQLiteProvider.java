package jp.ddo.pigsty.HabitBrowser.Util.SQLite;
import android.content.ContentResolver;
import android.net.Uri;
public class SQLiteProvider {
    public interface OnTransactionListener {}
    public static void beginTransaction(ContentResolver resolver, Uri uri, OnTransactionListener listener) {}
}