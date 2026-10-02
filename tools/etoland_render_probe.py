import json, os, re
from pathlib import Path
from playwright.sync_api import sync_playwright

URL=os.environ.get("TARGET_URL","https://etoland.co.kr/b/etohumor07/view/-9471921")
OUT=Path("render_probe_out"); OUT.mkdir(exist_ok=True)
UA=("Mozilla/5.0 (Linux; Android 16; SM-S938N Build/BP4A.251205.006; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
    "Chrome/153.0.8010.36 Mobile Safari/537.36")
interesting=re.compile(r"(mp4|m3u8|m4s|webm|mpd|video|player|embed|media)",re.I)
result={"target":URL,"console":[],"errors":[],"requests":[],"responses":[],"frames":[]}

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,args=["--no-sandbox","--autoplay-policy=no-user-gesture-required"])
    ctx=browser.new_context(user_agent=UA,viewport={"width":786,"height":1536},is_mobile=True,has_touch=True,locale="ko-KR",ignore_https_errors=True)
    page=ctx.new_page()
    page.on("console",lambda m: result["console"].append({"type":m.type,"text":m.text}))
    page.on("pageerror",lambda e: result["errors"].append(str(e)))
    page.on("request",lambda req: result["requests"].append({"type":req.resource_type,"url":req.url}) if req.resource_type in ("document","media","xhr","fetch") or interesting.search(req.url) else None)
    page.on("response",lambda resp: result["responses"].append({"status":resp.status,"type":resp.request.resource_type,"url":resp.url,"content_type":resp.headers.get("content-type","")}) if resp.request.resource_type in ("document","media","xhr","fetch") or interesting.search(resp.url) else None)

    try:
        page.goto(URL,wait_until="domcontentloaded",timeout=90000)
    except Exception as e:
        result["goto_error"]=repr(e)

    for _ in range(5):
        page.wait_for_timeout(2000)
        page.mouse.wheel(0,1200)

    page.wait_for_timeout(2000)
    try: page.screenshot(path=str(OUT/"page_full.png"),full_page=True)
    except Exception as e: result["screenshot_error"]=repr(e)
    try: (OUT/"top.html").write_text(page.content(),encoding="utf-8")
    except Exception: pass

    for idx,frame in enumerate(page.frames):
        info={"index":idx,"url":frame.url,"name":frame.name}
        for key,expr in {
            "videos":"""() => Array.from(document.querySelectorAll('video')).map((v,i)=>{const r=v.getBoundingClientRect();let q=null;try{q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null}catch(e){};return {i,currentSrc:v.currentSrc,src:v.src,paused:v.paused,currentTime:v.currentTime,duration:v.duration,readyState:v.readyState,networkState:v.networkState,error:v.error?{code:v.error.code,message:v.error.message}:null,videoWidth:v.videoWidth,videoHeight:v.videoHeight,rect:{x:r.x,y:r.y,width:r.width,height:r.height},frames:q?{total:q.totalVideoFrames,dropped:q.droppedVideoFrames}:null,style:{display:getComputedStyle(v).display,visibility:getComputedStyle(v).visibility,opacity:getComputedStyle(v).opacity,position:getComputedStyle(v).position,zIndex:getComputedStyle(v).zIndex}}})""",
            "iframes":"""() => Array.from(document.querySelectorAll('iframe')).map((f,i)=>{const r=f.getBoundingClientRect();return {i,src:f.src,rect:{x:r.x,y:r.y,width:r.width,height:r.height},allow:f.getAttribute('allow')}})""",
            "canvases":"""() => Array.from(document.querySelectorAll('canvas')).map((c,i)=>{const r=c.getBoundingClientRect();return {i,width:c.width,height:c.height,rect:{x:r.x,y:r.y,width:r.width,height:r.height},id:c.id,className:String(c.className||'')}})""",
            "large":"""() => Array.from(document.querySelectorAll('body *')).map((e,i)=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {i,tag:e.tagName,id:e.id||'',className:String(e.className||'').slice(0,100),rect:{x:r.x,y:r.y,width:r.width,height:r.height},bg:s.backgroundColor,position:s.position,zIndex:s.zIndex,opacity:s.opacity,visibility:s.visibility}}).filter(x=>x.rect.width>280&&x.rect.height>140).slice(0,80)"""
        }.items():
            try: info[key]=frame.evaluate(expr)
            except Exception as e: info[key]={"error":repr(e)}
        result["frames"].append(info)

    result["final_url"]=page.url
    result["frame_count"]=len(page.frames)
    browser.close()

(OUT/"probe.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"final_url":result.get("final_url"),"frame_count":result.get("frame_count"),"frames":[{"url":f["url"],"videos":len(f["videos"]) if isinstance(f.get("videos"),list) else f.get("videos")} for f in result["frames"]]},ensure_ascii=False,indent=2))
