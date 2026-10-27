// Ported from aresstack/cam-tool tool/src/ui/page_archiver.cpp (kCaptureJs, MIT), inline mode only.
// Changes against the original: the result is RETURNED to Playwright's evaluate() instead of being handed to the
// Qt callback window[CB] (emit() keeps it in a local variable), and the whole collector is one function value, so
// it declares nothing on the page. SPLIT, AS_JSON and MARK are fixed by HtmlCollector (inline JSON, no split).
async function(){
  var CB = "__CB__";
  var SPLIT = __SPLIT__;
  var AS_JSON = __JSON__;
  var MARK = "__MARKER__";
  var assets = [], used = {};
  var result = "";

  function emit(p){ result = p; }

  function pick(url, ext){
    var b = "";
    try { b = new URL(url, location.href).pathname.split("/").pop() || ""; } catch(e) {}
    b = b.replace(/[^A-Za-z0-9._-]/g, "_");
    if (!b) b = "asset";
    if (b.indexOf(".") < 0) b = b + (ext || "");
    var n = b, i = 1;
    while (used[n]) {
      var dot = b.lastIndexOf(".");
      n = (dot > 0 ? b.slice(0, dot) + "-" + i + b.slice(dot) : b + "-" + i);
      i++;
    }
    used[n] = 1;
    return n;
  }

  async function fetchDataUrl(url){
    try {
      var r = await fetch(url, {credentials: "same-origin"});
      if (!r.ok) return null;
      var b = await r.blob();
      if (b.size > 8*1024*1024) return null;          // do not bloat the archive
      return await new Promise(function(res){
        var fr = new FileReader();
        fr.onload = function(){ res(fr.result); };
        fr.onerror = function(){ res(null); };
        fr.readAsDataURL(b);
      });
    } catch(e) { return null; }
  }

  function b64of(dataUrl){
    var i = dataUrl.indexOf(",");
    return i < 0 ? "" : dataUrl.slice(i+1);
  }

  async function refFor(url, ext){
    var d = await fetchDataUrl(url);
    if (!d) return null;
    if (!SPLIT) return d;
    var n = pick(url, ext);
    assets.push({name: n, b64: b64of(d)});
    return MARK + n;
  }

  // url(...) inside CSS always becomes a data: URL, in both modes: that keeps a
  // stylesheet self-contained without a second level of relative paths.
  async function inlineCssUrls(css, baseUrl){
    var re = /url\(\s*(['"]?)([^'")]+)\1\s*\)/g, found = [], m;
    while ((m = re.exec(css))) {
      var raw = m[2];
      if (!/^data:/i.test(raw) && found.indexOf(raw) < 0) found.push(raw);
    }
    var pairs = await Promise.all(found.map(async function(raw){
      try { return [raw, await fetchDataUrl(new URL(raw, baseUrl).href)]; }
      catch(e) { return [raw, null]; }
    }));
    pairs.forEach(function(p){ if (p[1]) css = css.split(p[0]).join(p[1]); });
    return css;
  }

  try {
    var doc = document.documentElement.cloneNode(true);
    doc.querySelectorAll('script,link[rel~="preload"],link[rel~="modulepreload"]')
       .forEach(function(e){ e.remove(); });

    var links = [].slice.call(doc.querySelectorAll('link[rel~="stylesheet"][href]'));
    await Promise.all(links.map(async function(l){
      try {
        var href = new URL(l.getAttribute("href"), location.href).href;
        var r = await fetch(href);
        if (!r.ok) return;
        var css = await inlineCssUrls(await r.text(), href);
        if (SPLIT) {
          var n = pick(href, ".css");
          assets.push({name: n, b64: btoa(unescape(encodeURIComponent(css)))});
          l.setAttribute("href", MARK + n);
        } else {
          var s = document.createElement("style");
          s.setAttribute("data-from", href);
          s.textContent = css;
          l.parentNode.replaceChild(s, l);
        }
      } catch(e) {}
    }));

    var imgs = [].slice.call(doc.querySelectorAll("img[src]"));
    await Promise.all(imgs.map(async function(i){
      try {
        var src = i.getAttribute("src");
        if (!src || /^data:/i.test(src)) return;
        var ref = await refFor(new URL(src, location.href).href, ".png");
        if (ref) i.setAttribute("src", ref);
        i.removeAttribute("srcset");
      } catch(e) {}
    }));

    // Plain links become absolute so the copy never resolves against the local
    // file path.
    [].slice.call(doc.querySelectorAll("a[href]")).forEach(function(a){
      try { a.setAttribute("href", new URL(a.getAttribute("href"), location.href).href); }
      catch(e) {}
    });

    var html = "<!DOCTYPE html>\n" + doc.outerHTML;
    if (AS_JSON) {
      emit(JSON.stringify({title: document.title || "", url: location.href,
                           html: html, assets: assets}));
    } else {
      emit(html);
    }
  } catch(e) { emit(""); }
  return result;
}
