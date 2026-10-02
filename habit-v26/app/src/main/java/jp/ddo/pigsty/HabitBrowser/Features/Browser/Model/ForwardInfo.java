package jp.ddo.pigsty.HabitBrowser.Features.Browser.Model;

import android.os.Parcel;
import android.os.Parcelable;

public class ForwardInfo implements Parcelable {
    public static final Parcelable.Creator<ForwardInfo> CREATOR =
            new Parcelable.Creator<ForwardInfo>() {
                @Override
                public ForwardInfo createFromParcel(Parcel source) {
                    return new ForwardInfo(source);
                }

                @Override
                public ForwardInfo[] newArray(int size) {
                    return new ForwardInfo[size];
                }
            };

    public static final String INTENT_DATA_KEY_ID_LIST = "idlist";
    public static final String INTENT_DATA_KEY_ID_TYPE = "idtype";
    public static final String INTENT_DATA_KEY_NEWTAB = "newtab";
    public static final String INTENT_DATA_KEY_OPENFOLDER = "openfolder";
    public static final String INTENT_DATA_KEY_TITLE = "title";

    private int kind;
    private boolean newTab;
    private String title;
    private String url;

    public ForwardInfo() {
        url = null;
        title = null;
        newTab = false;
        kind = 0;
    }

    private ForwardInfo(Parcel source) {
        this();
        setUrl(source.readString());
        setTitle(source.readString());
        setNewTab(source.readInt() != 0);
        setKind(source.readInt());
    }

    @Override
    public int describeContents() { return 0; }

    public int getKind() { return kind; }
    public String getTitle() { return title; }
    public String getUrl() { return url; }
    public boolean isNewTab() { return newTab; }

    public void setKind(int kind) { this.kind = kind; }
    public void setNewTab(boolean newTab) { this.newTab = newTab; }
    public void setTitle(String title) { this.title = title; }
    public void setUrl(String url) { this.url = url; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getUrl());
        dest.writeString(getTitle());
        dest.writeInt(isNewTab() ? 1 : 0);
        dest.writeInt(getKind());
    }
}
