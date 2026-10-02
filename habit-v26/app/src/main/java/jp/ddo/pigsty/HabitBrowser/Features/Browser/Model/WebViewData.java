package jp.ddo.pigsty.HabitBrowser.Features.Browser.Model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * Java reconstruction of the V17 WebViewData parcelable model.
 *
 * Field descriptors and public method descriptors are kept compatible with
 * the legacy DEX class.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class WebViewData implements Parcelable {
    public static final Parcelable.Creator CREATOR = new WebViewData$1();

    private int index;
    private int size;
    private long tabId;
    private ArrayList titleList;
    private ArrayList urlList;
    private byte[] webViewBundle;
    private long webViewId;

    public WebViewData() {
        webViewId = 0L;
        tabId = 0L;
        index = 0;
        size = 0;
        urlList = new ArrayList();
        titleList = new ArrayList();
        webViewBundle = null;
    }

    WebViewData(Parcel in) {
        this();

        setWebViewId(in.readLong());
        setTabId(in.readLong());
        setIndex(in.readInt());
        setSize(in.readInt());

        in.readStringList(getUrlList());
        in.readStringList(getTitleList());

        int length = in.readInt();
        webViewBundle = new byte[length];
        in.readByteArray(webViewBundle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public int getIndex() {
        return index;
    }

    public int getSize() {
        return size;
    }

    public long getTabId() {
        return tabId;
    }

    public ArrayList getTitleList() {
        return titleList;
    }

    public ArrayList getUrlList() {
        return urlList;
    }

    public byte[] getWebViewBundle() {
        return webViewBundle;
    }

    public long getWebViewId() {
        return webViewId;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public void setTabId(long tabId) {
        this.tabId = tabId;
    }

    public void setTitleList(ArrayList titleList) {
        this.titleList = titleList;
    }

    public void setUrlList(ArrayList urlList) {
        this.urlList = urlList;
    }

    public void setWebViewBundle(byte[] webViewBundle) {
        this.webViewBundle = webViewBundle;
    }

    public void setWebViewId(long webViewId) {
        this.webViewId = webViewId;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(getWebViewId());
        dest.writeLong(getTabId());
        dest.writeInt(getIndex());
        dest.writeInt(getSize());
        dest.writeStringList(getUrlList());
        dest.writeStringList(getTitleList());
        dest.writeInt(getWebViewBundle().length);
        dest.writeByteArray(getWebViewBundle());
    }
}
