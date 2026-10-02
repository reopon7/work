package jp.ddo.pigsty.HabitBrowser.Features.Browser.Model;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Explicitly named replacement for the compiler-generated legacy
 * WebViewData$1 Parcelable.Creator class.
 */
final class WebViewData$1 implements Parcelable.Creator<WebViewData> {
    WebViewData$1() {
    }

    @Override
    public WebViewData createFromParcel(Parcel source) {
        return new WebViewData(source);
    }

    @Override
    public WebViewData[] newArray(int size) {
        return new WebViewData[size];
    }
}
