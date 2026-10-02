(function () {
  'use strict';

  if (window.__hbV25Installed) {
    if (window.__hbV25Scan) window.__hbV25Scan();
    return;
  }
  window.__hbV25Installed = true;

  function formatTime(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '0:00';
    var s = Math.floor(seconds);
    var m = Math.floor(s / 60);
    s %= 60;
    return m + ':' + (s < 10 ? '0' : '') + s;
  }

  function buttonStyle(button) {
    button.style.cssText =
      'width:38px;height:34px;flex:0 0 38px;' +
      'background:rgba(0,0,0,.58);color:#fff;' +
      'border:1px solid rgba(255,255,255,.75);border-radius:4px;' +
      'font:16px sans-serif;padding:0;margin:0;';
  }

  function drawVideo(ctx, video, width, height) {
    var sw = video.videoWidth || 0;
    var sh = video.videoHeight || 0;
    if (sw <= 0 || sh <= 0 || width <= 0 || height <= 0) return;

    var fit = 'contain';
    try {
      fit = getComputedStyle(video).objectFit || 'contain';
    } catch (ignore) {}

    var sx = 0, sy = 0, sourceW = sw, sourceH = sh;
    var dx = 0, dy = 0, drawW = width, drawH = height;

    if (fit === 'cover') {
      var sourceRatio = sw / sh;
      var destRatio = width / height;
      if (sourceRatio > destRatio) {
        sourceW = sh * destRatio;
        sx = (sw - sourceW) / 2;
      } else {
        sourceH = sw / destRatio;
        sy = (sh - sourceH) / 2;
      }
    } else if (fit !== 'fill') {
      var scale = Math.min(width / sw, height / sh);
      drawW = sw * scale;
      drawH = sh * scale;
      dx = (width - drawW) / 2;
      dy = (height - drawH) / 2;
    }

    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, width, height);
    ctx.drawImage(video, sx, sy, sourceW, sourceH, dx, dy, drawW, drawH);
  }

  function install(video) {
    if (!video || video.__hbV25) return;
    video.__hbV25 = true;

    var overlay = document.createElement('div');
    var canvas = document.createElement('canvas');
    var controls = document.createElement('div');
    var playButton = document.createElement('button');
    var seek = document.createElement('input');
    var time = document.createElement('span');
    var muteButton = document.createElement('button');
    var fullButton = document.createElement('button');

    overlay.className = 'hbv25-overlay';
    canvas.className = 'hbv25-canvas';
    controls.className = 'hbv25-controls';

    playButton.textContent = '❚❚';
    muteButton.textContent = video.muted ? '🔇' : '🔊';
    fullButton.textContent = '⛶';

    seek.type = 'range';
    seek.min = '0';
    seek.max = '1000';
    seek.value = '0';

    overlay.style.cssText =
      'position:fixed;z-index:2147483000;overflow:hidden;' +
      'background:#000;pointer-events:none;display:none;';
    canvas.style.cssText =
      'position:absolute;left:0;top:0;width:100%;height:100%;' +
      'display:block;background:#000;pointer-events:auto;';
    controls.style.cssText =
      'position:absolute;left:0;right:0;bottom:0;height:46px;' +
      'display:flex;align-items:center;gap:6px;padding:6px;' +
      'box-sizing:border-box;pointer-events:auto;color:#fff;' +
      'background:linear-gradient(transparent,rgba(0,0,0,.88));';

    buttonStyle(playButton);
    buttonStyle(muteButton);
    buttonStyle(fullButton);
    seek.style.cssText = 'flex:1;min-width:60px;margin:0;';
    time.style.cssText =
      'min-width:82px;text-align:center;color:#fff;' +
      'font:12px sans-serif;text-shadow:0 1px 2px #000;';

    controls.appendChild(playButton);
    controls.appendChild(seek);
    controls.appendChild(time);
    controls.appendChild(muteButton);
    controls.appendChild(fullButton);
    overlay.appendChild(canvas);
    overlay.appendChild(controls);
    document.documentElement.appendChild(overlay);

    var originalOpacity = video.style.opacity;
    var originalControls = video.controls;
    var seeking = false;
    var manualFullscreen = false;
    var lastVisible = false;
    var lastWidth = 0;
    var lastHeight = 0;

    try { video.controls = false; } catch (ignore) {}
    video.style.opacity = '0.001';

    function isDocumentFullscreen() {
      return document.fullscreenElement === overlay ||
             document.webkitFullscreenElement === overlay;
    }

    function place() {
      if (!document.documentElement.contains(video)) {
        try { overlay.remove(); } catch (ignore) {}
        return false;
      }

      if (manualFullscreen || isDocumentFullscreen()) {
        overlay.style.display = 'block';
        overlay.style.left = '0';
        overlay.style.top = '0';
        overlay.style.width = '100vw';
        overlay.style.height = '100vh';
        lastVisible = true;
        return true;
      }

      var r = video.getBoundingClientRect();
      var visible = r.width > 2 && r.height > 2 &&
        r.bottom > 0 && r.right > 0 &&
        r.top < innerHeight && r.left < innerWidth;

      overlay.style.display = visible ? 'block' : 'none';
      lastVisible = visible;
      if (!visible) return false;

      overlay.style.left = r.left + 'px';
      overlay.style.top = r.top + 'px';
      overlay.style.width = r.width + 'px';
      overlay.style.height = r.height + 'px';
      return true;
    }

    function resizeCanvas() {
      var r = overlay.getBoundingClientRect();
      var dpr = Math.min(2, window.devicePixelRatio || 1);
      var w = Math.max(2, Math.round(r.width * dpr));
      var h = Math.max(2, Math.round(r.height * dpr));
      if (w !== lastWidth || h !== lastHeight) {
        lastWidth = w;
        lastHeight = h;
        canvas.width = w;
        canvas.height = h;
      }
      return { width: w, height: h };
    }

    function drawFrame() {
      try {
        if (!place()) return;
        var size = resizeCanvas();
        if (video.readyState >= 2 && video.videoWidth > 0) {
          drawVideo(canvas.getContext('2d'), video, size.width, size.height);
        }
      } catch (ignore) {}
    }

    function updateControls() {
      try {
        if (!seeking && isFinite(video.duration) && video.duration > 0) {
          seek.value = String(Math.round((video.currentTime / video.duration) * 1000));
        }
        time.textContent = formatTime(video.currentTime) + ' / ' + formatTime(video.duration);
        playButton.textContent = video.paused ? '▶' : '❚❚';
        muteButton.textContent = video.muted ? '🔇' : '🔊';
      } catch (ignore) {}
    }

    function togglePlay() {
      try {
        if (video.paused) {
          var promise = video.play();
          if (promise && promise.catch) promise.catch(function () {});
        } else {
          video.pause();
        }
      } catch (ignore) {}
    }

    canvas.addEventListener('click', togglePlay, false);
    playButton.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      togglePlay();
    }, false);

    muteButton.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      video.muted = !video.muted;
      updateControls();
    }, false);

    seek.addEventListener('touchstart', function () { seeking = true; }, false);
    seek.addEventListener('mousedown', function () { seeking = true; }, false);
    seek.addEventListener('input', function () {
      try {
        if (isFinite(video.duration) && video.duration > 0) {
          video.currentTime = (Number(seek.value) / 1000) * video.duration;
          drawFrame();
          updateControls();
        }
      } catch (ignore) {}
    }, false);
    seek.addEventListener('change', function () { seeking = false; }, false);
    seek.addEventListener('touchend', function () { seeking = false; }, false);
    seek.addEventListener('mouseup', function () { seeking = false; }, false);

    fullButton.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      try {
        if (isDocumentFullscreen()) {
          if (document.exitFullscreen) document.exitFullscreen();
          else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
          return;
        }
        if (!manualFullscreen && overlay.requestFullscreen) {
          var p = overlay.requestFullscreen();
          if (p && p.catch) {
            p.catch(function () {
              manualFullscreen = true;
              place();
            });
          }
          return;
        }
        if (!manualFullscreen && overlay.webkitRequestFullscreen) {
          overlay.webkitRequestFullscreen();
          return;
        }
        manualFullscreen = !manualFullscreen;
        place();
      } catch (ignore) {
        manualFullscreen = !manualFullscreen;
        place();
      }
    }, false);

    function onFullscreenChange() {
      if (!isDocumentFullscreen()) manualFullscreen = false;
      place();
      drawFrame();
    }
    document.addEventListener('fullscreenchange', onFullscreenChange, false);
    document.addEventListener('webkitfullscreenchange', onFullscreenChange, false);

    ['loadeddata', 'loadedmetadata', 'canplay', 'playing', 'pause', 'seeked', 'resize']
      .forEach(function (name) {
        video.addEventListener(name, function () {
          drawFrame();
          updateControls();
        }, false);
      });

    window.addEventListener('scroll', place, true);
    window.addEventListener('resize', function () {
      place();
      drawFrame();
    }, false);

    if (typeof video.requestVideoFrameCallback === 'function') {
      var onVideoFrame = function () {
        drawFrame();
        updateControls();
        if (document.documentElement.contains(video)) {
          video.requestVideoFrameCallback(onVideoFrame);
        }
      };
      video.requestVideoFrameCallback(onVideoFrame);
    } else {
      var animationLoop = function () {
        if (!document.documentElement.contains(video)) return;
        if (lastVisible || !video.paused) {
          drawFrame();
          updateControls();
        } else {
          place();
        }
        requestAnimationFrame(animationLoop);
      };
      requestAnimationFrame(animationLoop);
    }

    var maintenance = setInterval(function () {
      if (!document.documentElement.contains(video)) {
        clearInterval(maintenance);
        try { video.style.opacity = originalOpacity; } catch (ignore) {}
        try { video.controls = originalControls; } catch (ignore) {}
        try { overlay.remove(); } catch (ignore) {}
        return;
      }
      place();
      updateControls();
      if (video.paused) drawFrame();
    }, 500);

    place();
    drawFrame();
    updateControls();
  }

  window.__hbV25Scan = function () {
    var videos = document.getElementsByTagName('video');
    for (var i = 0; i < videos.length; i++) install(videos[i]);
  };

  window.__hbV25Scan();

  try {
    new MutationObserver(function () {
      window.__hbV25Scan();
    }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (ignore) {}
})();
