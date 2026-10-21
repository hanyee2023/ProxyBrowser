// assets/sniffer.js —— 视频嗅探注入脚本
// 钩住 fetch / XHR / <video> / <source>，捕获媒体资源 URL，通过 JSBridge 上报给 Kotlin 层。
(function () {
  if (window.__pbSnifferInjected) return;
  window.__pbSnifferInjected = true;

  var MEDIA_EXT = /\.(m3u8|mp4|flv|m4s|ts|webm|mov|mkv|mpd)(\?|#|$)/i;
  var reported = Object.create(null);

  function pageTitle() {
    try { return (document.title || "").slice(0, 120); } catch (e) { return ""; }
  }

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return u; }
  }

  function report(url, source) {
    if (!url || typeof url !== "string") return;
    if (url.indexOf("data:") === 0 || url.indexOf("blob:") === 0) return;
    var full = abs(url);
    if (reported[full]) return;
    // 只认明显是媒体的链接，或视频播放器接口
    if (!MEDIA_EXT.test(full) && !/(m3u8|playlist|videoplayback|getplayinfo)/i.test(full)) return;
    reported[full] = 1;
    var type = "video";
    if (/\.m3u8|\.mpd/i.test(full)) type = "hls";
    else if (/\.mp4/i.test(full)) type = "mp4";
    try {
      if (window.PB && window.PB.report) {
        window.PB.report(JSON.stringify({
          url: full, type: type, page: location.href, title: pageTitle()
        }));
      }
    } catch (e) {}
  }

  // 1) <video> 播放时取 currentSrc
  document.addEventListener("play", function (e) {
    var v = e.target;
    if (v && v.tagName === "VIDEO" && v.currentSrc) report(v.currentSrc, "video");
  }, true);

  // 2) 扫描新增的 video / source 节点
  try {
    var obs = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes || [];
        for (var j = 0; j < added.length; j++) {
          var n = added[j];
          if (!n || n.nodeType !== 1) continue;
          if (n.tagName === "VIDEO") {
            if (n.src) report(n.src, "video-tag");
            if (n.currentSrc) report(n.currentSrc, "video-tag");
          } else if (n.tagName === "SOURCE" && n.src) {
            report(n.src, "source-tag");
          }
          if (n.querySelectorAll) {
            var vs = n.querySelectorAll("video[src],source[src]");
            for (var k = 0; k < vs.length; k++) report(vs[k].src, "scan");
          }
        }
      }
    });
    obs.observe(document.documentElement || document, { childList: true, subtree: true });
  } catch (e) {}

  // 3) 钩住 fetch
  var _fetch = window.fetch;
  if (_fetch) {
    window.fetch = function (input) {
      try {
        var u = typeof input === "string" ? input : (input && input.url);
        if (u) report(u, "fetch");
      } catch (e) {}
      return _fetch.apply(this, arguments);
    };
  }

  // 4) 钩住 XMLHttpRequest
  var _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, url) {
    try { if (url) report(url, "xhr"); } catch (e) {}
    return _open.apply(this, arguments);
  };
})();
