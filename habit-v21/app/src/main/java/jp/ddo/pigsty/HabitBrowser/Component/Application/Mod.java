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
 * V31 Java compatibility layer.
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

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new CompatCallbacks());
        Log.i(TAG, "V31 video + redirect guard initialized");
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

            // Keep WebView on a hardware layer. V23 confirmed layer=2/hw=true.
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            webView.invalidate();

            // Native guard wraps the legacy WebViewClient instead of replacing
            // its behavior. It blocks only known Coupang/AliExpress hijack URLs.
            RedirectGuard.install(webView);

            startInjectionLoop(webView);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply WebView compatibility settings", t);
        }
    }

    private static void startInjectionLoop(final WebView webView) {
        if (Build.VERSION.SDK_INT < 19) return;

        synchronized (STARTED) {
            if (STARTED.containsKey(webView)) return;
            STARTED.put(webView, Boolean.TRUE);
        }

        webView.postDelayed(new Runnable() {
            @Override public void run() {
                long nextDelay = 2000L;

                try {
                    // Do not keep dead WebViews alive forever. Removing the map
                    // marker allows a re-attached/recreated WebView to start a
                    // fresh loop later.
                    if (webView.getParent() == null && webView.getWindowToken() == null) {
                        synchronized (STARTED) {
                            STARTED.remove(webView);
                        }
                        return;
                    }

                    // Re-install if legacy code replaced the WebViewClient
                    // after Activity creation.
                    RedirectGuard.install(webView);

                    // JS-side guard catches touch/click/window.open/form/meta
                    // redirects before native navigation begins.
                    webView.evaluateJavascript(ANTI_HIJACK_JS, null);

                    String url = webView.getUrl();
                    if (url != null && url.contains("etoland.co.kr")) {
                        // Legacy Habit can still change layer policy while a tab
                        // lives for a long time. Reassert the V23-proven setting
                        // immediately before the canvas fallback is installed.
                        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        webView.invalidate();

                        webView.evaluateJavascript(CANVAS_VIDEO_FALLBACK_JS, null);
                        nextDelay = 750L;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "V30 video fallback injection failed", t);
                }

                try {
                    webView.postDelayed(this, nextDelay);
                } catch (Throwable ignored) {
                    synchronized (STARTED) {
                        STARTED.remove(webView);
                    }
                }
            }
        }, 400L);
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
        "};" +
        "window.__hbV24Scan();" +

        "try{" +
        " new MutationObserver(function(){window.__hbV24Scan();}).observe(document.documentElement,{childList:true,subtree:true});" +
        "}catch(e){}" +

        "}catch(e){}" +
        "})();";
}
