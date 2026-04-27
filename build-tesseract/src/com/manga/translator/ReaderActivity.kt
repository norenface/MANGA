package com.manga.translator

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.manga.translator.translation.MyMemoryTranslator
import com.manga.translator.ui.TranslatedImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_EPISODE = "episode_no"
        private const val TAG = "ReaderActivity"
        private const val TITLE_ID = "131385"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var ocrEngine: com.manga.translator.ocr.TesseractOcrEngine? = null

    private val downloadExecutor = Executors.newFixedThreadPool(3)
    private val ocrExecutor = Executors.newSingleThreadExecutor()

    private lateinit var scrollView: ScrollView
    private lateinit var pageContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var tvLoadingStatus: TextView
    private lateinit var navBar: View
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnToggleOcr: Button

    private var episodeNo = 1
    private val pageViews = ArrayList<TranslatedImageView>()
    private var overlaysVisible = true

    @Volatile private var loadEpoch = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_reader)
            episodeNo = intent.getIntExtra(EXTRA_EPISODE, 1)

            scrollView      = findViewById(R.id.scrollView)      as ScrollView
            pageContainer   = findViewById(R.id.pageContainer)   as LinearLayout
            loadingView     = findViewById(R.id.loadingView)
            tvLoadingStatus = findViewById(R.id.tvLoadingStatus) as TextView
            navBar          = findViewById(R.id.navBar)
            tvTitle         = findViewById(R.id.tvTitle)         as TextView
            btnClose        = findViewById(R.id.btnClose)        as Button
            btnPrev         = findViewById(R.id.btnPrev)         as Button
            btnNext         = findViewById(R.id.btnNext)         as Button
            btnToggleOcr    = findViewById(R.id.btnToggleOcr)    as Button

            setupControls()
            initTesseract()
            loadEpisode(episodeNo)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate: $t")
            finish()
        }
    }

    // ---- Tesseract 初期化 ----

    private fun initTesseract() {
        Thread {
            try {
                val engine = com.manga.translator.ocr.TesseractOcrEngine(applicationContext)
                if (engine.initialize()) {
                    ocrEngine = engine
                    Log.i(TAG, "Tesseract ready")
                    // 既に画像がロード済みのページに OCR を遡及実行
                    mainHandler.post { retryOcrOnLoadedPages() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Tesseract init: $t")
            }
        }.start()
    }

    // Tesseract 初期化完了時点で既に表示中の画像に OCR をかける
    private fun retryOcrOnLoadedPages() {
        val epoch = loadEpoch
        val engine = ocrEngine ?: return
        var i = 0
        while (i < pageViews.size) {
            val view = pageViews[i]
            val bmp = (view.drawable as? BitmapDrawable)?.bitmap
            if (bmp != null && !bmp.isRecycled) {
                val pageNum = i + 1
                val total = pageViews.size
                ocrExecutor.execute {
                    try {
                        if (epoch == loadEpoch) runOcr(engine, view, bmp, pageNum, total, epoch)
                    } catch (t: Throwable) { Log.e(TAG, "retryOcr$pageNum: $t") }
                }
            }
            i++
        }
    }

    // ---- UI 操作 ----

    private fun setupControls() {
        btnClose.setOnClickListener { finish() }
        btnPrev.setOnClickListener { if (episodeNo > 1) loadEpisode(--episodeNo) }
        btnNext.setOnClickListener { loadEpisode(++episodeNo) }
        btnToggleOcr.setOnClickListener {
            overlaysVisible = !overlaysVisible
            var i = 0; while (i < pageViews.size) { pageViews[i].toggleOverlays(); i++ }
            btnToggleOcr.text = if (overlaysVisible) "翻訳非表示" else "翻訳表示"
        }
        scrollView.setOnClickListener { toggleNavBar() }
    }

    // ---- エピソード読み込み ----

    private fun loadEpisode(no: Int) {
        loadEpoch++
        val epoch = loadEpoch
        episodeNo = no
        tvTitle.text = "第${no}話"
        pageViews.clear()
        pageContainer.removeAllViews()
        loadingView.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        btnToggleOcr.visibility = View.GONE
        showStatus("エピソード ${no} を読み込み中…")
        Thread { fetchImageUrls(no, epoch) }.start()
    }

    private fun fetchImageUrls(no: Int, epoch: Int) {
        try {
            val html = fetchHtml(
                "https://m.comic.naver.com/webtoon/detail?titleId=$TITLE_ID&no=$no"
            )
            val urls = extractImageUrls(html)
            mainHandler.post {
                try {
                    if (epoch != loadEpoch) return@post
                    if (urls.isNotEmpty()) startLoadingPages(urls, epoch)
                    else showStatus("画像URLが見つかりませんでした (エピソード $no)")
                } catch (t: Throwable) { Log.e(TAG, "startPages: $t") }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchImageUrls: $t")
            mainHandler.post {
                try { if (epoch == loadEpoch) showStatus("読込エラー: ${t.javaClass.simpleName}") }
                catch (_: Throwable) {}
            }
        }
    }

    private fun fetchHtml(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,*/*")
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.setRequestProperty("Referer", "https://m.comic.naver.com/")
            conn.connect()
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn?.disconnect() }
    }

    private fun extractImageUrls(html: String): ArrayList<String> {
        val urls = ArrayList<String>()
        val base = "image-comic.pstatic.net"
        val p1 = Regex(""""(https://$base/[^"]+)"""")
        p1.findAll(html).forEach { m -> val u = m.groupValues[1]; if (!urls.contains(u)) urls.add(u) }
        if (urls.isEmpty()) {
            val p2 = Regex("""src=["'](https://$base/[^"']+)["']""")
            p2.findAll(html).forEach { m -> val u = m.groupValues[1]; if (!urls.contains(u)) urls.add(u) }
        }
        if (urls.isEmpty()) {
            val p3 = Regex("""https://$base/[^\s"'<>\\]+""")
            p3.findAll(html).forEach { m -> val u = m.value; if (!urls.contains(u)) urls.add(u) }
        }
        return urls
    }

    // ---- ページ描画 ----

    private fun startLoadingPages(urls: ArrayList<String>, epoch: Int) {
        if (epoch != loadEpoch) return
        loadingView.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        btnToggleOcr.visibility = View.VISIBLE
        val total = urls.size
        var i = 0
        while (i < urls.size) {
            val pageNum = i + 1
            val url = urls[i]
            val imgView = try {
                TranslatedImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_XY
                    setBackgroundColor(android.graphics.Color.parseColor("#222222"))
                }
            } catch (t: Throwable) { Log.e(TAG, "view: $t"); i++; continue }
            try { pageContainer.addView(imgView); pageViews.add(imgView) }
            catch (t: Throwable) { Log.e(TAG, "addView: $t"); i++; continue }
            downloadExecutor.execute {
                try { if (epoch == loadEpoch) loadAndProcessPage(imgView, url, pageNum, total, epoch) }
                catch (t: Throwable) { Log.e(TAG, "page$pageNum: $t") }
            }
            i++
        }
    }

    private fun loadAndProcessPage(view: TranslatedImageView, url: String, pageNum: Int, total: Int, epoch: Int) {
        val bitmap = try { downloadBitmap(url) } catch (t: Throwable) { null } ?: return
        if (epoch != loadEpoch) { bitmap.recycle(); return }

        mainHandler.post {
            try {
                if (epoch == loadEpoch) {
                    view.setImageBitmap(bitmap)
                    showStatus("$pageNum / $total ページ読込済")
                } else bitmap.recycle()
            } catch (t: Throwable) { Log.e(TAG, "setImage: $t") }
        }

        val engine = ocrEngine ?: return  // まだ初期化中なら retryOcrOnLoadedPages で補完
        ocrExecutor.execute {
            try { if (epoch == loadEpoch) runOcr(engine, view, bitmap, pageNum, total, epoch) }
            catch (t: Throwable) { Log.e(TAG, "ocr$pageNum: $t") }
        }
    }

    private fun runOcr(
        engine: com.manga.translator.ocr.TesseractOcrEngine,
        view: TranslatedImageView,
        bitmap: Bitmap,
        pageNum: Int,
        total: Int,
        epoch: Int
    ) {
        if (epoch != loadEpoch) return

        // RGB_565 → ARGB_8888 コピーを作成 (Tesseract は ARGB_8888 で動作が安定)
        val ocrBmp = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) { bitmap }

        try {
            val overlays = engine.processImage(ocrBmp)
            if (overlays.isEmpty() || epoch != loadEpoch) return

            val originals = ArrayList<String>()
            var i = 0; while (i < overlays.size) { originals.add(overlays[i].originalText); i++ }

            val translations = try {
                MyMemoryTranslator.translateBatch(originals)
            } catch (_: Throwable) { originals }

            var j = 0
            while (j < overlays.size) {
                overlays[j].translatedText =
                    if (j < translations.size) translations[j] else overlays[j].originalText
                j++
            }
            mainHandler.post {
                try {
                    if (epoch == loadEpoch) {
                        view.setOverlays(overlays)
                        if (pageNum == total) showStatus("")
                    }
                } catch (t: Throwable) { Log.e(TAG, "overlay: $t") }
            }
        } finally {
            if (ocrBmp !== bitmap) ocrBmp.recycle()
        }
    }

    // ---- 画像ダウンロード ----

    private fun downloadBitmap(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Referer", "https://comic.naver.com")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            conn.connect()
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.readBytes()
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val screenW = resources.displayMetrics.widthPixels
            opts.inSampleSize = calcSampleSize(opts.outWidth, screenW)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) { Log.e(TAG, "dl: $t"); null }
        finally { conn?.disconnect() }
    }

    private fun calcSampleSize(imgW: Int, targetW: Int): Int {
        if (imgW <= 0 || targetW <= 0) return 1
        var s = 1; while (imgW / (s * 2) >= targetW) s *= 2; return s
    }

    private fun showStatus(msg: String) {
        if (msg.isBlank()) { loadingView.visibility = View.GONE; return }
        tvLoadingStatus.text = msg
        Log.d(TAG, msg)
    }

    private fun toggleNavBar() {
        navBar.visibility = if (navBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() { super.onBackPressed() }

    override fun onDestroy() {
        super.onDestroy()
        try {
            loadEpoch++
            downloadExecutor.shutdown()
            ocrExecutor.shutdown()
            ocrEngine?.release()
            ocrEngine = null
        } catch (t: Throwable) { Log.e(TAG, "onDestroy: $t") }
    }
}
