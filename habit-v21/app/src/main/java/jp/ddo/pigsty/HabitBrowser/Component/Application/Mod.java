package jp.ddo.pigsty.HabitBrowser.Component.Application;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Java-compiled WebView compatibility + diagnostics layer for the V17 baseline.
 *
 * No legacy HabitBrowser class bytecode is patched by this class.
 */
public final class Mod extends App {
    private static final String TAG = "HabitJavaCompat";
    private static final Map<WebView, Boolean> DIAG_STARTED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new CompatCallbacks());
        Log.i(TAG, "V22 Java compatibility layer initialized");
    }

    private static final class CompatCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) { scheduleApply(activity); }
        @Override public void onActivityStarted(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityResumed(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void scheduleApply(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        final View root = activity.getWindow().getDecorView();
        if (root == null) return;

        applyTree(root);

        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 250L);

        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 1200L);

        root.postDelayed(new Runnable() {
            @Override public void run() { applyTree(root); }
        }, 3000L);
    }

    private static void applyTree(View view) {
        if (view == null) return;

        if (view instanceof WebView) {
            applyWebView((WebView) view);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTree(group.getChildAt(i));
            }
        }
    }

    private static void applyWebView(final WebView webView) {
        try {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);

            if (Build.VERSION.SDK_INT >= 17) {
                settings.setMediaPlaybackRequiresUserGesture(false);
            }

            if (Build.VERSION.SDK_INT >= 21) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) {
                cookies.setAcceptThirdPartyCookies(webView, true);
            }

            startDiagnosticsLoop(webView);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply WebView compatibility settings", t);
        }
    }

    private static void startDiagnosticsLoop(final WebView webView) {
        if (Build.VERSION.SDK_INT < 19) return;
        synchronized (DIAG_STARTED) {
            if (DIAG_STARTED.containsKey(webView)) return;
            DIAG_STARTED.put(webView, Boolean.TRUE);
        }

        webView.postDelayed(new Runnable() {
            private int remaining = 60;

            @Override public void run() {
                if (remaining-- <= 0) return;
                try {
                    String url = webView.getUrl();
                    if (url != null && url.contains("etoland.co.kr")) {
                        webView.evaluateJavascript(DIAG_JS, null);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "diagnostic injection failed", t);
                }
                try {
                    webView.postDelayed(this, 1500L);
                } catch (Throwable ignored) {
                }
            }
        }, 1000L);
    }

    private static final String DIAG_JS =
            "(function(){try{" +
            "var W=window;if(!W.__hbv22log)W.__hbv22log=[];" +
            "function L(x){try{W.__hbv22log.push((new Date()).toLocaleTimeString()+' '+x);if(W.__hbv22log.length>18)W.__hbv22log.shift();}catch(e){}}" +
            "function S(x){return x==null?'':String(x);}" +
            "function E(x){return S(x).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}" +
            "var vs=document.getElementsByTagName('video');" +
            "for(var i=0;i<vs.length;i++){var v=vs[i];if(!v.__hbv22){" +
            "v.__hbv22=1;['error','abort','stalled','suspend','emptied','loadedmetadata','canplay','playing','waiting','pause'].forEach(function(n){v.addEventListener(n,function(ev){var q=ev.currentTarget;L(n+' rs='+q.readyState+' ns='+q.networkState+' err='+(q.error?q.error.code:'0')+' src='+S(q.currentSrc).slice(0,180));},true);});" +
            "v.addEventListener('click',function(){L('video click rs='+this.readyState+' ns='+this.networkState+' src='+S(this.currentSrc).slice(0,180));},true);" +
            "}}" +
            "var out=[];out.push('<b>Habit V22 media diag</b>');" +
            "out.push('url: '+E(location.href));out.push('ua: '+E(navigator.userAgent));" +
            "out.push('video count: '+vs.length);" +
            "var probe=document.createElement('video');" +
            "out.push('canPlay mp4/h264: '+E(probe.canPlayType('video/mp4')));" +
            "out.push('canPlay webm/vp9: '+E(probe.canPlayType('video/webm')));" +
            "out.push('canPlay hls: '+E(probe.canPlayType('application/vnd.apple.mpegurl')));" +
            "var ms='no';try{ms=!!W.MediaSource;if(ms&&MediaSource.isTypeSupported)ms='yes h264='+MediaSource.isTypeSupported('video/mp4');}catch(e){}out.push('MediaSource: '+E(ms));" +
            "for(var j=0;j<vs.length;j++){var x=vs[j];var er=x.error;out.push('V'+j+': paused='+x.paused+' rs='+x.readyState+' ns='+x.networkState+' dur='+x.duration+' size='+x.videoWidth+'x'+x.videoHeight+' err='+(er?(er.code+':'+S(er.message)):'none'));out.push('V'+j+' currentSrc: '+E(S(x.currentSrc)));out.push('V'+j+' src: '+E(S(x.getAttribute('src'))));var ss=x.getElementsByTagName('source');for(var k=0;k<ss.length;k++)out.push(' source '+k+': '+E(S(ss[k].src))+' ['+E(S(ss[k].type))+']');}" +
            "var fs=document.getElementsByTagName('iframe');out.push('iframe count: '+fs.length);for(var z=0;z<Math.min(fs.length,8);z++)out.push('F'+z+': '+E(S(fs[z].src)));" +
            "try{var pe=performance.getEntriesByType('resource'),m=[];for(var p=0;p<pe.length;p++){var n=pe[p].name||'',it=pe[p].initiatorType||'';if(/\\.(mp4|m3u8|m4s|ts|webm|mpd)(\\?|$)/i.test(n)||it==='video'||it==='media')m.push(it+' '+n);}out.push('media resources: '+m.length);for(var q=Math.max(0,m.length-8);q<m.length;q++)out.push(E(m[q]));}catch(e){out.push('perf err: '+E(e));}" +
            "if(W.__hbv22log.length){out.push('<b>events</b>');for(var r=0;r<W.__hbv22log.length;r++)out.push(E(W.__hbv22log[r]));}" +
            "var d=document.getElementById('habitV22Diag');if(!d){d=document.createElement('div');d.id='habitV22Diag';d.style.cssText='position:fixed;left:4px;right:4px;bottom:4px;z-index:2147483647;max-height:46vh;overflow:auto;background:rgba(0,0,0,.92);color:#fff;font:10px/1.3 monospace;padding:7px;border:1px solid #777;text-align:left;white-space:normal;';document.documentElement.appendChild(d);}d.innerHTML=out.join('<br>');" +
            "}catch(e){}})();";
}
