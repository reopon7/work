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
 * V44 Java compatibility layer.
 *
 * Legacy HabitBrowser classes.dex remains untouched.
 * This class is compiled from Java and added as classes2.dex.
 *
 * V30 keeps the proven V24 canvas-backed HTML5 video renderer and removes the
 * V23 proved that decode/playback succeeds while the native video compositor
 * still paints a gray rectangle.
 */
public final class Mod extends App {
    private static final String TAG = "HabitJavaCompat";
    private static final Map<WebView, Boolean> STARTED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());
    private static final Map<View, Boolean> ROOT_SCANS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    private static final Map<WebView, Boolean> CONFIGURED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());
    private static final Map<WebView, String> ETOLAND_PREPARED_URL =
            Collections.synchronizedMap(new WeakHashMap<WebView, String>());
    private static final Map<WebView, String> GUARDED_URL =
            Collections.synchronizedMap(new WeakHashMap<WebView, String>());

    @Override
    public void onCreate() {
        super.onCreate();
        sanitizeLegacyPreferences(false);
        registerActivityLifecycleCallbacks(new CompatCallbacks());
        Log.i(TAG, "V44 fullscreen swipe-seek initialized");
    }

    private static final class CompatCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) { scheduleApply(activity); }
        @Override public void onActivityStarted(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityResumed(Activity activity) { scheduleApply(activity); }
        @Override public void onActivityPaused(Activity activity) {
            NativeVideoOverlay.hideAll(activity);
        }
        @Override public void onActivityStopped(Activity activity) {
            NativeVideoOverlay.hideAll(activity);
        }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {
            NativeVideoOverlay.destroyAll(activity);
        }
    }

    private static void scheduleApply(Activity activity) {
        sanitizeLegacyPreferences(true);
        if (activity == null || activity.getWindow() == null) return;
        final View root = activity.getWindow().getDecorView();
        if (root == null) return;

        applyTree(root);

        synchronized (ROOT_SCANS) {
            if (ROOT_SCANS.containsKey(root)) {
                return;
            }
            ROOT_SCANS.put(root, Boolean.TRUE);
        }

        root.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (root.getWindowToken() == null) {
                        synchronized (ROOT_SCANS) {
                            ROOT_SCANS.remove(root);
                        }
                        return;
                    }

                    // Import can overwrite SharedPreferences while the Browser
                    // activity stays alive. Re-check continuously instead of
                    // only at Activity creation/resume.
                    sanitizeLegacyPreferences(true);

                    // Fast-back/tab cache detaches and later re-attaches
                    // WebViews. Rescan the live hierarchy so a reused WebView
                    // gets compatibility settings and its video loop restarted.
                    applyTree(root);
                } catch (Throwable t) {
                    Log.e(TAG, "V39 root compatibility scan failed", t);
                }

                try {
                    root.postDelayed(this, 1500L);
                } catch (Throwable ignored) {
                    synchronized (ROOT_SCANS) {
                        ROOT_SCANS.remove(root);
                    }
                }
            }
        }, 500L);
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
        if (webView == null) return;

        synchronized (CONFIGURED) {
            if (CONFIGURED.containsKey(webView)) {
                startInjectionLoop(webView);
                return;
            }
        }

        try {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);

            String ua = settings.getUserAgentString();
            if (isLegacyImportedUserAgent(ua)) {
                settings.setUserAgentString(null);
            }

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

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            RedirectGuard.install(webView);

            synchronized (CONFIGURED) {
                CONFIGURED.put(webView, Boolean.TRUE);
            }

            startInjectionLoop(webView);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply WebView compatibility settings", t);
        }
    }

    /**
     * Old Habit backups can carry two rendering/JavaScript options that were
     * reasonable workarounds on Android 4.x but are actively harmful with a
     * modern System WebView:
     *
     * conf_javascript_safe=true
     *   -> maps to isStopHeavyJavaScript and schedules WebView.onPause() /
     *      pauseTimers() shortly after navigation.
     *
     * conf_contents_force_draw=true
     *   -> calls WebView.invalidate() every ~2 s up to ten times after a page
     *      finishes.
     *
     * conf_content_old_video_replace=true
     *   -> injects the legacy video replacement path.
     *
     * V35 neutralizes only these obsolete compatibility toggles. Bookmarks,
     * gestures, toolbar/layout, search engines and other imported preferences
     * stay untouched.
     */
    private static void sanitizeLegacyPreferences(boolean refreshRuntimeConfig) {
        boolean changed = false;

        changed |= ensureBooleanPreference("conf_javascript_safe", false);
        changed |= ensureBooleanPreference("conf_contents_force_draw", false);
        changed |= ensureBooleanPreference("conf_content_old_video_replace", false);

        if (changed && refreshRuntimeConfig) {
            refreshLegacyConfig();
        }
    }

    private static boolean ensureBooleanPreference(String key, boolean wanted) {
        try {
            boolean current = App.getPreferenceBoolean(key, wanted);
            if (current != wanted) {
                App.setPreferenceBoolean(key, wanted);
                Log.i(TAG, "Sanitized imported preference: " + key + "=" + wanted);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Preference sanitize failed: " + key, t);
        }
        return false;
    }

    private static void refreshLegacyConfig() {
        try {
            Class<?> cls = Class.forName(
                    "jp.ddo.pigsty.HabitBrowser.Features.Browser.MainController");

            Object existsValue = cls.getMethod("existsInstance").invoke(null);
            if (!(existsValue instanceof Boolean) || !((Boolean) existsValue)) {
                return;
            }

            Object instance = cls.getMethod("getInstance").invoke(null);
            if (instance != null) {
                cls.getMethod("applyConfigrationStatus").invoke(instance);
                Log.i(TAG, "Reloaded legacy configuration after import sanitize");
            }
        } catch (Throwable t) {
            // Startup can legitimately reach this before MainController exists.
            Log.d(TAG, "Legacy config refresh deferred", t);
        }
    }

    private static boolean isLegacyImportedUserAgent(String ua) {
        if (ua == null) return false;
        return ua.contains("Android 4.0.1") ||
               ua.contains("Chrome/18.0.1025") ||
               ua.contains("Chrome/37.0.2062") ||
               ua.contains("iPhone OS 8_0_2") ||
               ua.contains("CPU OS 8_0_2");
    }

    private static void startInjectionLoop(final WebView webView) {
        if (Build.VERSION.SDK_INT < 19) return;

        synchronized (STARTED) {
            if (STARTED.containsKey(webView)) return;
            STARTED.put(webView, Boolean.TRUE);
        }

        webView.postDelayed(new Runnable() {
            @Override public void run() {
                long nextDelay = 2500L;

                try {
                    if (webView.getParent() == null || webView.getWindowToken() == null) {
                        NativeVideoOverlay.destroy(webView);
                        synchronized (STARTED) { STARTED.remove(webView); }
                        synchronized (CONFIGURED) { CONFIGURED.remove(webView); }
                        synchronized (ETOLAND_PREPARED_URL) { ETOLAND_PREPARED_URL.remove(webView); }
                        synchronized (GUARDED_URL) { GUARDED_URL.remove(webView); }
                        return;
                    }

                    RedirectGuard.install(webView);

                    String url = webView.getUrl();

                    String guarded;
                    synchronized (GUARDED_URL) {
                        guarded = GUARDED_URL.get(webView);
                    }
                    if (url != null && !url.equals(guarded)) {
                        webView.evaluateJavascript(ANTI_HIJACK_JS, null);
                        webView.evaluateJavascript(FULLSCREEN_SEEK_GESTURE_JS, null);
                        synchronized (GUARDED_URL) {
                            GUARDED_URL.put(webView, url);
                        }
                    }

                    if (url != null && url.contains("etoland.co.kr")) {
                        String prepared;
                        synchronized (ETOLAND_PREPARED_URL) {
                            prepared = ETOLAND_PREPARED_URL.get(webView);
                        }

                        if (!url.equals(prepared)) {
                            try { webView.onResume(); } catch (Throwable ignored) {}
                            try { webView.resumeTimers(); } catch (Throwable ignored) {}

                            WebSettings s = webView.getSettings();
                            s.setJavaScriptEnabled(true);
                            s.setDomStorageEnabled(true);
                            s.setLoadsImagesAutomatically(true);
                            s.setBlockNetworkImage(false);
                            s.setBlockNetworkLoads(false);
                            if (Build.VERSION.SDK_INT >= 21) {
                                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                            }
                            if (Build.VERSION.SDK_INT >= 17) {
                                s.setMediaPlaybackRequiresUserGesture(false);
                            }

                            // Clear imported legacy/custom UA only when entering
                            // a new Etoland document, not every polling tick.
                            s.setUserAgentString(null);
                            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

                            synchronized (ETOLAND_PREPARED_URL) {
                                ETOLAND_PREPARED_URL.put(webView, url);
                            }
                        }

                        // V42 probes source/play state at low frequency. Smooth
                        // scrolling is handled natively by ViewTreeObserver,
                        // avoiding evaluateJavascript + layout churn every 250 ms.
                        NativeVideoOverlay.update(webView);
                        nextDelay = 1200L;
                    } else {
                        NativeVideoOverlay.destroy(webView);
                        synchronized (ETOLAND_PREPARED_URL) {
                            ETOLAND_PREPARED_URL.remove(webView);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "V44 native video loop failed", t);
                }

                try {
                    webView.postDelayed(this, nextDelay);
                } catch (Throwable ignored) {
                    synchronized (STARTED) { STARTED.remove(webView); }
                }
            }
        }, 500L);
    }

    private static final String ANTI_HIJACK_JS =
        "(function(){" +
        "try{" +
        "if(window.__hbV31GuardInstalled)return;" +
        "window.__hbV31GuardInstalled=1;" +
        "function blocked(raw){" +
        " if(!raw)return false;" +
        " var s=String(raw).toLowerCase();" +
        " if(s.indexOf('coupang:')===0||s.indexOf('aliexpress:')===0)return true;" +
        " if(s.indexOf('intent:')===0||s.indexOf('market:')===0){" +
        "  return s.indexOf('coupang')>=0||s.indexOf('com.coupang.mobile')>=0||s.indexOf('aliexpress')>=0||s.indexOf('com.alibaba.aliexpresshd')>=0;" +
        " }" +
        " try{" +
        "  var u=new URL(raw,document.baseURI),h=(u.hostname||'').toLowerCase();" +
        "  function hm(root){return h===root||h.slice(-(root.length+1))==='.'+root;}" +
        "  return hm('coupang.com')||h==='coupang.page.link'||hm('aliexpress.com')||hm('aliexpress.us')||hm('aliexpress.ru')||hm('aliexpress.kr')||h==='ali.pub'||h.slice(-8)==='.ali.pub'||h==='ali.ski'||h.slice(-8)==='.ali.ski';" +
        " }catch(e){return false;}" +
        "}" +
        "function hrefOf(t){" +
        " while(t&&t!==document){if(t.href)return t.href;t=t.parentNode;}" +
        " return null;" +
        "}" +
        "function stopIfBlocked(e){" +
        " var h=hrefOf(e.target);" +
        " if(blocked(h)){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();return false;}" +
        "}" +
        "document.addEventListener('click',stopIfBlocked,true);" +
        "document.addEventListener('auxclick',stopIfBlocked,true);" +
        "document.addEventListener('touchend',stopIfBlocked,true);" +
        "document.addEventListener('submit',function(e){" +
        " try{var a=e.target&&e.target.action;if(blocked(a)){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation();}}catch(x){}" +
        "},true);" +
        "var oldOpen=window.open;" +
        "window.open=function(u,n,f){" +
        " if(blocked(u))return null;" +
        " return oldOpen?oldOpen.call(window,u,n,f):null;" +
        "};" +
        "function cleanMeta(){" +
        " try{" +
        "  var a=document.querySelectorAll('meta[http-equiv]');" +
        "  for(var i=0;i<a.length;i++){" +
        "   var m=a[i],v=(m.getAttribute('http-equiv')||'').toLowerCase();" +
        "   if(v!=='refresh')continue;" +
        "   var c=m.getAttribute('content')||'',p=c.toLowerCase().indexOf('url=');" +
        "   if(p>=0&&blocked(c.slice(p+4).trim()))m.parentNode&&m.parentNode.removeChild(m);" +
        "  }" +
        " }catch(e){}" +
        "}" +
        "cleanMeta();" +
        "try{new MutationObserver(cleanMeta).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}" +
        "}catch(e){}" +
        "})();";

    /**
     * Fullscreen horizontal swipe-to-seek.
     *
     * - single-finger horizontal drag anywhere on fullscreen video
     * - tap still behaves normally; gesture activates only after direction lock
     * - vertical movement is never claimed
     * - seek is committed once on finger-up to avoid decoder thrash
     * - short clips: one screen width can traverse the whole clip
     * - long clips: one screen width is capped at 10 minutes
     * - compact HUD shows target time and delta while dragging
     */
    private static final String FULLSCREEN_SEEK_GESTURE_JS =
        "(function(){" +
        "try{" +
        "if(window.__hbV44SeekInstalled)return;" +
        "window.__hbV44SeekInstalled=1;" +

        "var s={down:0,locked:0,cancel:0,x0:0,y0:0,start:0,target:0,dur:0,wasPaused:1,v:null,hud:null,bar:null,fill:null};" +

        "function clamp(v,a,b){return Math.max(a,Math.min(b,v));}" +
        "function fmt(t){" +
        " t=Math.max(0,Math.floor(Number(t)||0));" +
        " var h=Math.floor(t/3600),m=Math.floor((t%3600)/60),x=t%60;" +
        " if(h>0)return h+':' +(m<10?'0':'')+m+':' +(x<10?'0':'')+x;" +
        " return m+':' +(x<10?'0':'')+x;" +
        "}" +

        "function visible(v){" +
        " if(!v)return false;" +
        " try{" +
        "  var r=v.getBoundingClientRect(),cs=getComputedStyle(v);" +
        "  return r.width>8&&r.height>8&&cs.display!=='none'&&cs.visibility!=='hidden'&&Number(cs.opacity||1)>0.01;" +
        " }catch(e){return false;}" +
        "}" +

        "function fsRoot(){" +
        " return document.fullscreenElement||document.webkitFullscreenElement||document.mozFullScreenElement||document.msFullscreenElement||null;" +
        "}" +

        "function pickVideo(){" +
        " var root=fsRoot(),v=null;" +
        " try{" +
        "  if(root){" +
        "   if(root.tagName&&String(root.tagName).toLowerCase()==='video')v=root;" +
        "   if(!v&&root.querySelector)v=root.querySelector('video');" +
        "  }" +
        " }catch(e){}" +
        " if(v&&visible(v))return v;" +

        " var a=document.getElementsByTagName('video'),best=null,bestArea=0;" +
        " for(var i=0;i<a.length;i++){" +
        "  var q=a[i];if(!visible(q))continue;" +
        "  var r=q.getBoundingClientRect();" +
        "  var area=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
        "  if(area>bestArea){best=q;bestArea=area;}" +
        " }" +
        " if(!best)return null;" +

        " try{" +
        "  if(best.webkitDisplayingFullscreen)return best;" +
        " }catch(e){}" +

        " if(root)return best;" +

        " try{" +
        "  var br=best.getBoundingClientRect();" +
        "  var cover=(br.width*br.height)/(Math.max(1,innerWidth*innerHeight));" +
        "  var landscape=innerWidth>innerHeight;" +
        "  if(landscape&&cover>=0.72)return best;" +
        " }catch(e){}" +
        " return null;" +
        "}" +

        "function ensureHud(){" +
        " if(s.hud&&document.documentElement.contains(s.hud))return;" +
        " var h=document.createElement('div');" +
        " h.id='__hbV44SeekHud';" +
        " h.style.cssText='position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);z-index:2147483647;min-width:150px;padding:12px 16px 10px;border-radius:12px;background:rgba(0,0,0,.72);color:#fff;font:600 18px/1.25 sans-serif;text-align:center;pointer-events:none;opacity:0;transition:opacity .08s linear;box-sizing:border-box;';" +
        " var t=document.createElement('div');t.id='__hbV44SeekText';h.appendChild(t);" +
        " var b=document.createElement('div');" +
        " b.style.cssText='height:3px;margin-top:9px;background:rgba(255,255,255,.28);border-radius:2px;overflow:hidden;';" +
        " var f=document.createElement('div');" +
        " f.style.cssText='height:100%;width:0;background:#fff;border-radius:2px;';" +
        " b.appendChild(f);h.appendChild(b);" +
        " document.documentElement.appendChild(h);" +
        " s.hud=h;s.bar=t;s.fill=f;" +
        "}" +

        "function showHud(){" +
        " ensureHud();" +
        " var d=s.target-s.start,sign=d>=0?'+':'−';" +
        " s.bar.textContent=fmt(s.target)+' / '+fmt(s.dur)+'   '+sign+fmt(Math.abs(d));" +
        " s.fill.style.width=(s.dur>0?clamp((s.target/s.dur)*100,0,100):0)+'%';" +
        " s.hud.style.opacity='1';" +
        "}" +
        "function hideHud(){" +
        " if(s.hud)s.hud.style.opacity='0';" +
        "}" +

        "function reset(){" +
        " s.down=0;s.locked=0;s.cancel=0;s.v=null;hideHud();" +
        "}" +

        "function onStart(e){" +
        " if(!e.touches||e.touches.length!==1){reset();return;}" +
        " var v=pickVideo();" +
        " if(!v)return;" +
        " var dur=Number(v.duration);" +
        " if(!isFinite(dur)||dur<=0)return;" +
        " var p=e.touches[0];" +
        " s.down=1;s.locked=0;s.cancel=0;s.x0=p.clientX;s.y0=p.clientY;" +
        " s.start=Number(v.currentTime)||0;s.target=s.start;s.dur=dur;s.wasPaused=!!v.paused;s.v=v;" +
        "}" +

        "function onMove(e){" +
        " if(!s.down||!s.v||!e.touches||e.touches.length!==1)return;" +
        " var p=e.touches[0],dx=p.clientX-s.x0,dy=p.clientY-s.y0;" +
        " if(!s.locked){" +
        "  if(Math.abs(dx)<18&&Math.abs(dy)<18)return;" +
        "  if(Math.abs(dy)>Math.abs(dx)*1.05){s.cancel=1;reset();return;}" +
        "  if(Math.abs(dx)<=Math.abs(dy)*1.15)return;" +
        "  s.locked=1;" +
        " }" +
        " if(!s.locked)return;" +
        " if(e.cancelable)e.preventDefault();" +
        " if(e.stopImmediatePropagation)e.stopImmediatePropagation();else e.stopPropagation();" +
        " var span=Math.min(s.dur,600);" +
        " var w=Math.max(240,innerWidth||screen.width||1080);" +
        " s.target=clamp(s.start+(dx/w)*span,0,Math.max(0,s.dur-.05));" +
        " showHud();" +
        "}" +

        "function onEnd(e){" +
        " if(!s.down)return;" +
        " var v=s.v,commit=s.locked&&!s.cancel&&v;" +
        " if(commit){" +
        "  if(e&&e.cancelable)e.preventDefault();" +
        "  if(e&&e.stopImmediatePropagation)e.stopImmediatePropagation();else if(e)e.stopPropagation();" +
        "  try{" +
        "   v.currentTime=s.target;" +
        "   if(!s.wasPaused){var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
        "  }catch(x){}" +
        " }" +
        " reset();" +
        "}" +

        "document.addEventListener('touchstart',onStart,{capture:true,passive:true});" +
        "document.addEventListener('touchmove',onMove,{capture:true,passive:false});" +
        "document.addEventListener('touchend',onEnd,{capture:true,passive:false});" +
        "document.addEventListener('touchcancel',reset,{capture:true,passive:true});" +

        "function fsChanged(){reset();}" +
        "document.addEventListener('fullscreenchange',fsChanged,true);" +
        "document.addEventListener('webkitfullscreenchange',fsChanged,true);" +
        "document.addEventListener('mozfullscreenchange',fsChanged,true);" +
        "document.addEventListener('MSFullscreenChange',fsChanged,true);" +
        "}catch(e){}" +
        "})();";

    /**
     * V23 observations on the failing Etoland MP4:
     * - readyState=4
     * - networkState=1
     * - paused=false
     * - currentTime increases
     * - decoded frames increase, droppedFrames=0
     * - drawImage(video -> canvas) renders correct frames
     * - native <video> rectangle itself remains gray
     *
     * Therefore V24 keeps the original video as the decoder/audio source,
     * makes its broken visual output transparent, and mirrors decoded frames
     * into a DOM canvas positioned exactly over the video rectangle.
     *
     * A small custom control bar is provided because native video controls
     * would otherwise be hidden together with the broken compositor surface.
     */
    private static final String CANVAS_VIDEO_FALLBACK_JS =
        "(function(){" +
        "try{" +
        "if(window.__hbV24Installed){window.__hbV24Scan&&window.__hbV24Scan();return;}" +
        "window.__hbV24Installed=1;" +

        "function clamp(v,a,b){return Math.max(a,Math.min(b,v));}" +

        "function formatTime(s){" +
        " if(!isFinite(s)||s<0)return '0:00';" +
        " s=Math.floor(s);var m=Math.floor(s/60),x=s%60;" +
        " return m+':' +(x<10?'0':'')+x;" +
        "}" +

        "function drawContain(ctx,v,w,h){" +
        " var sw=v.videoWidth||1,sh=v.videoHeight||1;" +
        " if(sw<=0||sh<=0)return;" +
        " var fit='contain';" +
        " try{fit=getComputedStyle(v).objectFit||'contain';}catch(e){}" +
        " var sx=0,sy=0,sdw=sw,sdh=sh,dx=0,dy=0,dw=w,dh=h;" +
        " if(fit==='cover'){" +
        "  var sr=sw/sh,dr=w/h;" +
        "  if(sr>dr){sdw=sh*dr;sx=(sw-sdw)/2;}else{sdh=sw/dr;sy=(sh-sdh)/2;}" +
        " }else if(fit!=='fill'){" +
        "  var scale=Math.min(w/sw,h/sh);" +
        "  dw=sw*scale;dh=sh*scale;dx=(w-dw)/2;dy=(h-dh)/2;" +
        " }" +
        " ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);" +
        " ctx.drawImage(v,sx,sy,sdw,sdh,dx,dy,dw,dh);" +
        "}" +

        "function install(v){" +
        " if(!v||v.__hbV24)return;" +
        " v.__hbV24=1;" +

        " var box=document.createElement('div');" +
        " var cv=document.createElement('canvas');" +
        " var bar=document.createElement('div');" +
        " var play=document.createElement('button');" +
        " var seek=document.createElement('input');" +
        " var tm=document.createElement('span');" +
        " var mute=document.createElement('button');" +
        " var fs=document.createElement('button');" +

        " box.className='hbv24-box';" +
        " cv.className='hbv24-canvas';" +
        " bar.className='hbv24-bar';" +
        " play.textContent='❚❚';" +
        " mute.textContent=v.muted?'🔇':'🔊';" +
        " fs.textContent='⛶';" +
        " seek.type='range';seek.min='0';seek.max='1000';seek.value='0';" +

        " box.style.cssText='position:fixed;z-index:2147483000;overflow:hidden;background:#000;pointer-events:none;';" +
        " cv.style.cssText='position:absolute;left:0;top:0;width:100%;height:100%;display:block;pointer-events:auto;background:#000;';" +
        " bar.style.cssText='position:absolute;left:0;right:0;bottom:0;height:44px;display:flex;align-items:center;gap:6px;padding:4px 6px;box-sizing:border-box;background:linear-gradient(transparent,rgba(0,0,0,.82));color:#fff;font:12px sans-serif;pointer-events:auto;';" +
        " play.style.cssText='width:38px;height:34px;background:rgba(0,0,0,.55);color:#fff;border:1px solid #aaa;border-radius:4px;';" +
        " mute.style.cssText=play.style.cssText;" +
        " fs.style.cssText=play.style.cssText;" +
        " seek.style.cssText='flex:1;min-width:60px;';" +
        " tm.style.cssText='min-width:78px;text-align:center;color:#fff;text-shadow:0 1px 2px #000;';" +

        " bar.appendChild(play);bar.appendChild(seek);bar.appendChild(tm);bar.appendChild(mute);bar.appendChild(fs);" +
        " box.appendChild(cv);box.appendChild(bar);document.documentElement.appendChild(box);" +

        " try{v.controls=false;}catch(e){}" +
        " v.style.opacity='0.001';" +

        " function toggle(){" +
        "  if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else{v.pause();}" +
        " }" +
        " cv.addEventListener('click',toggle,false);" +
        " play.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();toggle();},false);" +
        " mute.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();v.muted=!v.muted;mute.textContent=v.muted?'🔇':'🔊';},false);" +

        " var seeking=0;" +
        " seek.addEventListener('touchstart',function(){seeking=1;},false);" +
        " seek.addEventListener('mousedown',function(){seeking=1;},false);" +
        " seek.addEventListener('input',function(){if(isFinite(v.duration)&&v.duration>0)v.currentTime=(Number(seek.value)/1000)*v.duration;},false);" +
        " seek.addEventListener('change',function(){seeking=0;},false);" +
        " seek.addEventListener('touchend',function(){seeking=0;},false);" +
        " seek.addEventListener('mouseup',function(){seeking=0;},false);" +

        " fs.addEventListener('click',function(e){" +
        "  e.preventDefault();e.stopPropagation();" +
        "  try{" +
        "   if(document.fullscreenElement===box){document.exitFullscreen&&document.exitFullscreen();}" +
        "   else if(box.requestFullscreen){box.requestFullscreen();}" +
        "   else if(box.webkitRequestFullscreen){box.webkitRequestFullscreen();}" +
        "  }catch(x){}" +
        " },false);" +

        " function place(){" +
        "  var full=(document.fullscreenElement===box)||(document.webkitFullscreenElement===box);" +
        "  if(full){" +
        "   box.style.left='0';box.style.top='0';box.style.width='100vw';box.style.height='100vh';box.style.display='block';return;" +
        "  }" +
        "  var r=v.getBoundingClientRect();" +
        "  var visible=r.width>2&&r.height>2&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;" +
        "  box.style.display=visible?'block':'none';" +
        "  if(!visible)return;" +
        "  box.style.left=r.left+'px';box.style.top=r.top+'px';box.style.width=r.width+'px';box.style.height=r.height+'px';" +
        " }" +

        " function frame(){" +
        "  try{" +
        "   if(!document.documentElement.contains(v)){box.remove();return;}" +
        "   place();" +
        "   var r=box.getBoundingClientRect();" +
        "   var dpr=Math.min(2,window.devicePixelRatio||1);" +
        "   var w=Math.max(2,Math.round(r.width*dpr)),h=Math.max(2,Math.round(r.height*dpr));" +
        "   if(cv.width!==w||cv.height!==h){cv.width=w;cv.height=h;}" +
        "   var ctx=cv.getContext('2d');" +
        "   if(v.readyState>=2&&v.videoWidth>0){drawContain(ctx,v,w,h);}" +
        "   if(!seeking&&isFinite(v.duration)&&v.duration>0)seek.value=String(Math.round((v.currentTime/v.duration)*1000));" +
        "   tm.textContent=formatTime(v.currentTime)+' / '+formatTime(v.duration);" +
        "   play.textContent=v.paused?'▶':'❚❚';" +
        "  }catch(e){}" +
        "  requestAnimationFrame(frame);" +
        " }" +
        " requestAnimationFrame(frame);" +
        "}" +

        "window.__hbV24Scan=function(){" +
        " var a=document.getElementsByTagName('video');" +
        " for(var i=0;i<a.length;i++)install(a[i]);" +
        " try{" +
        "  var all=document.querySelectorAll('*');" +
        "  for(var j=0;j<all.length;j++){" +
        "   var sr=all[j].shadowRoot;if(!sr)continue;" +
        "   var sv=sr.querySelectorAll('video');" +
        "   for(var k=0;k<sv.length;k++)install(sv[k]);" +
        "  }" +
        " }catch(e){}" +
        "};" +
        "window.__hbV24Scan();" +

        "try{" +
        " new MutationObserver(function(){window.__hbV24Scan();}).observe(document.documentElement,{childList:true,subtree:true});" +
        "}catch(e){}" +

        "}catch(e){}" +
        "})();";
    /*
     * V32: same-origin iframe video fallback.
     *
     * Some Etoland posts create the media element inside an iframe instead of
     * the top document. The V24 renderer only scanned the top document, so
     * those videos remained gray even though decoding worked.
     */
    private static final String NESTED_VIDEO_FALLBACK_JS =
        "(function(){" +
        "try{" +
        "function boot(w){" +
        " try{" +
        "  if(!w||!w.document||w.__hbV32FrameInstalled)return;" +
        "  w.__hbV32FrameInstalled=1;" +
        "  var d=w.document;" +
        "  function fit(ctx,v,W,H){" +
        "   var sw=v.videoWidth||0,sh=v.videoHeight||0;if(sw<1||sh<1)return;" +
        "   var s=Math.min(W/sw,H/sh),dw=sw*s,dh=sh*s,dx=(W-dw)/2,dy=(H-dh)/2;" +
        "   ctx.fillStyle='#000';ctx.fillRect(0,0,W,H);" +
        "   ctx.drawImage(v,0,0,sw,sh,dx,dy,dw,dh);" +
        "  }" +
        "  function install(v){" +
        "   if(!v||v.__hbV32Nested)return;v.__hbV32Nested=1;" +
        "   var box=d.createElement('div'),cv=d.createElement('canvas');" +
        "   box.style.cssText='position:fixed;z-index:2147483000;overflow:hidden;background:#000;pointer-events:none;';" +
        "   cv.style.cssText='position:absolute;inset:0;width:100%;height:100%;background:#000;pointer-events:auto;display:block;';" +
        "   box.appendChild(cv);d.documentElement.appendChild(box);" +
        "   try{v.controls=false;v.style.opacity='0.001';}catch(e){}" +
        "   cv.addEventListener('click',function(){try{if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else v.pause();}catch(e){}},false);" +
        "   function frame(){" +
        "    try{" +
        "     if(!d.documentElement.contains(v)){box.remove();return;}" +
        "     var r=v.getBoundingClientRect();" +
        "     var vis=r.width>2&&r.height>2&&r.bottom>0&&r.right>0&&r.top<w.innerHeight&&r.left<w.innerWidth;" +
        "     box.style.display=vis?'block':'none';" +
        "     if(vis){" +
        "      box.style.left=r.left+'px';box.style.top=r.top+'px';box.style.width=r.width+'px';box.style.height=r.height+'px';" +
        "      var pr=Math.min(2,w.devicePixelRatio||1),W=Math.max(2,Math.round(r.width*pr)),H=Math.max(2,Math.round(r.height*pr));" +
        "      if(cv.width!==W||cv.height!==H){cv.width=W;cv.height=H;}" +
        "      if(v.readyState>=2&&v.videoWidth>0)fit(cv.getContext('2d'),v,W,H);" +
        "     }" +
        "    }catch(e){}" +
        "    w.requestAnimationFrame(frame);" +
        "   }" +
        "   w.requestAnimationFrame(frame);" +
        "  }" +
        "  function scan(){" +
        "   try{" +
        "    var a=d.getElementsByTagName('video');for(var i=0;i<a.length;i++)install(a[i]);" +
        "    var fs=d.getElementsByTagName('iframe');" +
        "    for(var j=0;j<fs.length;j++){" +
        "     try{fs[j].setAttribute('allow','autoplay; fullscreen; picture-in-picture');fs[j].setAttribute('allowfullscreen','');}catch(e){}" +
        "     try{boot(fs[j].contentWindow);}catch(e){}" +
        "    }" +
        "   }catch(e){}" +
        "  }" +
        "  scan();" +
        "  try{new w.MutationObserver(scan).observe(d.documentElement,{childList:true,subtree:true});}catch(e){}" +
        "  w.setInterval(scan,750);" +
        " }catch(e){}" +
        "}" +
        "try{" +
        " var fs=document.getElementsByTagName('iframe');" +
        " for(var i=0;i<fs.length;i++){" +
        "  try{fs[i].setAttribute('allow','autoplay; fullscreen; picture-in-picture');fs[i].setAttribute('allowfullscreen','');}catch(e){}" +
        "  try{boot(fs[i].contentWindow);}catch(e){}" +
        " }" +
        "}catch(e){}" +
        "}catch(e){}" +
        "})();";
    @SuppressWarnings("unused")
    private static final String FRAME_DIAG_JS =
        "(function(){" +
        "try{" +
        "function S(v){return v==null?'':String(v);}" +
        "function E(v){return S(v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}" +
        "function R(el){try{var r=el.getBoundingClientRect();return Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height);}catch(e){return '?';}}" +
        "var out=[];out.push('<b>Habit V33 frame diag</b>');out.push('url: '+E(location.href));" +
        "var vs=document.getElementsByTagName('video');out.push('top videos: '+vs.length);" +
        "for(var i=0;i<vs.length;i++){var v=vs[i];out.push('V'+i+' rect='+R(v)+' rs='+v.readyState+' ns='+v.networkState+' paused='+v.paused+' size='+v.videoWidth+'x'+v.videoHeight+' src='+E(v.currentSrc||v.src));}" +
        "var fs=document.getElementsByTagName('iframe');out.push('iframes: '+fs.length);" +
        "for(var j=0;j<fs.length;j++){var f=fs[j],same='?';var vc='?';try{var d=f.contentDocument;if(d){same='YES';vc=d.getElementsByTagName('video').length;}else same='NO';}catch(e){same='CROSS';}out.push('F'+j+' '+same+' videos='+vc+' rect='+R(f)+' allow='+E(f.getAttribute('allow'))+' src='+E(f.src));}" +
        "var os=document.querySelectorAll('object,embed');out.push('object/embed: '+os.length);" +
        "for(var k=0;k<os.length;k++){var o=os[k];out.push(o.tagName+k+' rect='+R(o)+' type='+E(o.type)+' src='+E(o.src||o.data));}" +
        "var cs=document.getElementsByTagName('canvas');out.push('canvas: '+cs.length);for(var q=0;q<Math.min(cs.length,12);q++)out.push('C'+q+' rect='+R(cs[q])+' size='+cs[q].width+'x'+cs[q].height);" +
        "try{var all=document.querySelectorAll('body *'),large=[];for(var a=0;a<all.length;a++){var x=all[a],r=x.getBoundingClientRect();if(r.width>280&&r.height>140){var st=getComputedStyle(x),bg=st.backgroundColor;if(bg&&bg!=='rgba(0, 0, 0, 0)'&&bg!=='transparent'){large.push(x.tagName+'.'+S(x.className).slice(0,45)+' rect='+R(x)+' bg='+bg);if(large.length>=10)break;}}}out.push('<b>large bg elements</b>');for(var b=0;b<large.length;b++)out.push(E(large[b]));}catch(e){}" +
        "try{var pe=performance.getEntriesByType('resource'),m=[];for(var p=0;p<pe.length;p++){var n=pe[p].name||'',it=pe[p].initiatorType||'';if(/(mp4|m3u8|m4s|webm|mpd|video|player|embed)/i.test(n)||it==='video'||it==='media'||it==='iframe')m.push(it+' '+n);}out.push('<b>media/frame resources '+m.length+'</b>');for(var t=Math.max(0,m.length-15);t<m.length;t++)out.push(E(m[t]));}catch(e){}" +
        "var d=document.getElementById('hbV33Diag');if(!d){d=document.createElement('div');d.id='hbV33Diag';d.style.cssText='position:fixed;left:4px;right:4px;bottom:4px;z-index:2147483647;max-height:55vh;overflow:auto;background:rgba(0,0,0,.94);color:#fff;font:10px/1.3 monospace;padding:7px;border:1px solid #888;text-align:left;';document.documentElement.appendChild(d);}d.innerHTML=out.join('<br>');" +
        "}catch(e){}" +
        "})();";


}
