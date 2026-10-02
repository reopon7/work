package jp.ddo.pigsty.HabitBrowser.Features.Browser;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Model.ConfigrationStatus;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Util.TabManager;
import jp.ddo.pigsty.HabitBrowser.Features.Browser.Util.WebViewManager;
public class MainController {
    public static boolean existsInstance() { return false; }
    public static MainController getInstance() { return null; }
    public ConfigrationStatus getConfigrationStatus() { return null; }
    public TabManager getTabManager() { return null; }
    public WebViewManager getWebViewManager() { return null; }
    public BrowserActivity getActivity() { return null; }
}