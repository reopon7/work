package jp.ddo.pigsty.HabitBrowser.Features.SettingPattern.Util;

import java.util.ArrayList;
import java.util.Iterator;

import jp.ddo.pigsty.HabitBrowser.Features.SettingPattern.Model.SettingPatternInfo;
import jp.ddo.pigsty.HabitBrowser.Features.UrlPattern.Util.UrlPatternManager;
import jp.ddo.pigsty.HabitBrowser.Util.Is;
import jp.ddo.pigsty.HabitBrowser.Util.Thread.ThreadUtil;

/**
 * Java reconstruction of V17 SettingPatternManager.
 *
 * The legacy SettingPatternManager$1 persistence reader remains in classes.dex
 * for now and resolves this Java outer class through access$000().
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SettingPatternManager {
    private static final ArrayList patternList = new ArrayList();

    public SettingPatternManager() {
    }

    static ArrayList access$000() {
        return patternList;
    }

    public static SettingPatternInfo getSettingPattern(String url) {
        if (Is.isBlank(url)) {
            return null;
        }

        String lowerUrl = url.toLowerCase();
        Iterator it = patternList.iterator();

        while (it.hasNext()) {
            SettingPatternInfo info =
                    (SettingPatternInfo) it.next();

            if (UrlPatternManager.isMatch(
                    lowerUrl,
                    info.getRulesArray(),
                    info.isEndWildcard())) {
                return info;
            }
        }

        return null;
    }

    public static void init() {
        read();
    }

    public static void read() {
        ThreadUtil.runRowPriority(new SettingPatternManager$1());
    }
}
