package jp.ddo.pigsty.HabitBrowser.Features.Browser.Util;

import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import jp.ddo.pigsty.HabitBrowser.Features.Bookmark.Model.BookmarkInfo;
import jp.ddo.pigsty.HabitBrowser.Features.Bookmark.Table.TableBookmark;
import jp.ddo.pigsty.HabitBrowser.Features.History.Model.HistoryInfo;
import jp.ddo.pigsty.HabitBrowser.Features.History.Table.TableHistory;
import jp.ddo.pigsty.HabitBrowser.Util.Is;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ForwardManager {
    public ForwardManager() {}

    public static Intent createForwardIntentData(
            int type, String title, long[] idList, boolean newTab) {
        Intent intent = new Intent();
        intent.putExtra("idtype", type);
        intent.putExtra("title", title);
        intent.putExtra("idlist", idList);
        intent.putExtra("newtab", newTab ? 1 : 0);
        return intent;
    }

    public static String getTitle(Intent intent) {
        if (intent.hasExtra("title")) {
            return intent.getStringExtra("title");
        }
        return null;
    }

    public static int getType(Intent intent) {
        int result = 0;
        if (intent.hasExtra("idtype")) {
            result = intent.getIntExtra("idtype", 0);
        }
        return result;
    }

    public static String getUrl(Intent intent) {
        String result = null;
        if (!intent.hasExtra("idlist")) {
            return null;
        }

        long[] ids = intent.getLongArrayExtra("idlist");
        if (ids == null || ids.length == 0) {
            return null;
        }

        switch (intent.getIntExtra("idtype", 0)) {
            case 0: {
                BookmarkInfo info = TableBookmark.select(ids[0]);
                if (info != null) {
                    result = info.getUrl();
                }
                break;
            }
            case 4: {
                HistoryInfo info = TableHistory.select(ids[0]);
                if (info != null) {
                    result = info.getUrl();
                }
                break;
            }
            default:
                break;
        }
        return result;
    }

    public static List getUrlList(Intent intent) {
        List result = new ArrayList();
        if (!intent.hasExtra("idlist")) {
            return result;
        }

        long[] ids = intent.getLongArrayExtra("idlist");
        if (ids == null || ids.length == 0) {
            return result;
        }

        switch (getType(intent)) {
            case 0:
                for (long id : ids) {
                    BookmarkInfo info = TableBookmark.select(id);
                    if (info != null && !Is.isBlank(info.getUrl())) {
                        result.add(info.getUrl());
                    }
                }
                break;
            case 4:
                for (long id : ids) {
                    HistoryInfo info = TableHistory.select(id);
                    if (info != null && !Is.isBlank(info.getUrl())) {
                        result.add(info.getUrl());
                    }
                }
                break;
            default:
                break;
        }

        return result;
    }

    public static boolean isNewTab(Intent intent) {
        if (intent.hasExtra("newtab")) {
            return intent.getIntExtra("newtab", 1) != 0;
        }
        return true;
    }
}
