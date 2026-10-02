package jp.ddo.pigsty.HabitBrowser.Features.Browser.View;
import android.content.Context;
import android.webkit.WebView;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Util.TabClient;
import jp.ddo.pigsty.HabitBrowser.Features.SettingPattern.Model.SettingPatternInfo;
public class HabitWebView extends WebView {
    public boolean isLoadingStarted;
    public HabitWebView(Context context) { super(context); }
    public long getTabId() { return 0L; }
    public long getWebViewId() { return 0L; }
    public void setWebViewId(long id) {}
    public void init(TabClient tab) {}
    public void close() {}
    public void applySettings() {}
    public void setNetwrok(boolean enabled) {}
    public String getPageUrl() { return null; }
    public String getPageTitle() { return null; }
    public void setSettingPatternInfo(SettingPatternInfo info) {}
    public void doPause() {}
    public void doResume() {}\n    public TabClient getTab() { return null; }
}