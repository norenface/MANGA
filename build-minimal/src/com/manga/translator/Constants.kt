package com.manga.translator

object Constants {
    const val TITLE_ID = "131385"
    private const val TRANSLATE_BASE = "https://m-comic-naver-com.translate.goog"
    private const val TR = "&_x_tr_sl=ko&_x_tr_tl=ja&_x_tr_hl=ja&_x_tr_pto=wapp"

    fun episodeListUrl() =
        "$TRANSLATE_BASE/webtoon/list?titleId=$TITLE_ID&week=thu$TR"

    fun episodeUrl(no: Int) =
        "$TRANSLATE_BASE/webtoon/detail?titleId=$TITLE_ID&no=$no$TR"

    fun toTranslated(url: String): String {
        if (url.contains("translate.goog")) return url
        return url
            .replace("https://m.comic.naver.com", TRANSLATE_BASE)
            .replace("https://comic.naver.com", TRANSLATE_BASE) + TR
    }

    // OCR+翻訳用JavaScriptの注入コード (OCR.space + Google Translate)
    val OCR_OVERLAY_JS = """
        (function() {
            if (window._ocrInjected) return;
            window._ocrInjected = true;

            // CSS for overlays
            var style = document.createElement('style');
            style.textContent = [
                '.ocr-container { position:relative; display:block; }',
                '.ocr-overlay {',
                '  position:absolute; background:rgba(255,252,200,0.93);',
                '  border:1.5px solid rgba(200,80,0,0.7); border-radius:4px;',
                '  padding:2px 4px; font-family:"Noto Sans JP",sans-serif;',
                '  color:#111; z-index:9999; pointer-events:none;',
                '  line-height:1.3; word-break:break-all; font-size:13px; }',
                '.ocr-processing { opacity:0.5; }'
            ].join('');
            document.head.appendChild(style);

            // Translate single string via Google's unofficial API
            async function translateKoJa(text) {
                if (!text || !text.trim()) return text;
                try {
                    var url = 'https://translate.googleapis.com/translate_a/single'
                        + '?client=gtx&sl=ko&tl=ja&dt=t&q='
                        + encodeURIComponent(text);
                    var resp = await fetch(url);
                    var data = await resp.json();
                    return (data[0] || []).map(function(p){return p[0]||'';}).join('');
                } catch(e) { return text; }
            }

            // Process single image via OCR.space (sends URL directly - no CORS issue)
            async function processImage(img) {
                var src = img.src || img.getAttribute('data-src');
                if (!src || src.indexOf('image-comic.pstatic.net') < 0) return;
                if (img._ocrDone) return;
                img._ocrDone = true;

                // Wrap in container
                if (!img.parentNode.classList.contains('ocr-container')) {
                    var wrap = document.createElement('div');
                    wrap.className = 'ocr-container';
                    wrap.style.width = img.offsetWidth + 'px';
                    img.parentNode.insertBefore(wrap, img);
                    wrap.appendChild(img);
                }
                var container = img.parentNode;
                img.classList.add('ocr-processing');

                try {
                    var fd = new FormData();
                    fd.append('url', src);
                    fd.append('language', 'kor');
                    fd.append('isOverlayRequired', 'true');
                    fd.append('detectOrientation', 'true');
                    fd.append('scale', 'true');

                    var resp = await fetch('https://api.ocr.space/parse/image', {
                        method: 'POST',
                        headers: { 'apikey': 'helloworld' },
                        body: fd
                    });
                    var result = await resp.json();
                    img.classList.remove('ocr-processing');

                    if (!result.ParsedResults || !result.ParsedResults[0]) return;
                    var overlay = result.ParsedResults[0].TextOverlay;
                    if (!overlay || !overlay.Lines) return;

                    var imgW = result.ParsedResults[0].TextOverlay.Message
                        ? 0 : (img.naturalWidth || img.width);
                    var dispW = img.offsetWidth || img.clientWidth || imgW;
                    var scale = imgW > 0 ? (dispW / imgW) : 1.0;

                    for (var li = 0; li < overlay.Lines.length; li++) {
                        var line = overlay.Lines[li];
                        var lineText = line.LineText;
                        if (!lineText.trim()) continue;

                        var minLeft = Infinity, minTop = Infinity;
                        var maxRight = 0, maxBottom = 0;
                        for (var wi = 0; wi < line.Words.length; wi++) {
                            var w = line.Words[wi];
                            minLeft = Math.min(minLeft, w.Left);
                            minTop = Math.min(minTop, w.Top);
                            maxRight = Math.max(maxRight, w.Left + w.Width);
                            maxBottom = Math.max(maxBottom, w.Top + w.Height);
                        }

                        var translated = await translateKoJa(lineText);

                        var div = document.createElement('div');
                        div.className = 'ocr-overlay';
                        var boxH = (maxBottom - minTop) * scale;
                        div.style.left = (minLeft * scale) + 'px';
                        div.style.top = (minTop * scale) + 'px';
                        div.style.width = ((maxRight - minLeft) * scale) + 'px';
                        div.style.minHeight = boxH + 'px';
                        div.style.fontSize = Math.max(10, Math.min(boxH * 0.65, 24)) + 'px';
                        div.textContent = translated;
                        container.appendChild(div);
                    }
                } catch(e) {
                    img.classList.remove('ocr-processing');
                    console.log('OCR error:', e);
                }
            }

            // Run OCR on all manga images
            window.runOcrOnPage = async function() {
                var imgs = document.querySelectorAll(
                    'img[src*="image-comic.pstatic.net"],' +
                    '#comic_view_area img, .viewer_lst img, ._img'
                );
                for (var i = 0; i < imgs.length; i++) {
                    await processImage(imgs[i]);
                }
            };
        })();
    """.trimIndent()
}
