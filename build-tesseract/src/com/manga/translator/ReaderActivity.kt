package com.manga.translator

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.manga.translator.ocr.TesseractOcrEngine
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
    private lateinit var ocrEngine: TesseractOcrEngine

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

        ocrEngine = TesseractOcrEngine(this)
        setupControls()
        initTesseract()
        loadEpisode(episodeNo)
    }

    private fun initTesseract() {
        Thread {
            val ok = ocrEngine.initialize()
            if (!ok) mainHandler.post { showStatus("OCR初期化失敗 (翻訳オーバーレイ無効)") }
        }.start()
    }

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
        // WebViewを使わず HTTP で直接 HTML を取得して画像URLを抽出する
        Thread { fetchImageUrls(no, epoch) }.start()
    }

    private fun fetchImageUrls(no: Int, epoch: Int) {
        try {
            val html = fetchHtml(
                "https://m.comic.naver.com/webtoon/detail?titleId=$TITLE_ID&no=$no"
            )
            val urls = extractImageUrls(html)
            mainHandler.post {
                if (epoch != loadEpoch) return@post
                if (urls.isNotEmpty()) {
                    startLoadingPages(urls, epoch)
                } else {
                    showStatus("画像URLが見つかりませんでした\n(エピソード $no)")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fetchImageUrls error: $t")
            mainHandler.post {
                if (epoch == loadEpoch) showStatus("読込エラー:\n${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun fetchHtml(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,ja;q=0.8")
            conn.setRequestProperty("Referer", "https://m.comic.naver.com/")
            conn.connect()
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractImageUrls(html: String): ArrayList<String> {
        val urls = ArrayList<String>()
        val base = "image-comic.pstatic.net"

        // パターン1: JSON/JS 文字列内の URL ("url":"https://...")
        val p1 = Regex(""""(https://$base/[^"]+)"""")
        p1.findAll(html).forEach { m ->
            val u = m.groupValues[1]
            if (!urls.contains(u)) urls.add(u)
        }
        // パターン2: img src 属性
        if (urls.isEmpty()) {
            val p2 = Regex("""src=["'](https://$base/[^"']+)["']""")
            p2.findAll(html).forEach { m ->
                val u = m.groupValues[1]
                if (!urls.contains(u)) urls.add(u)
            }
        }
        // パターン3: 任意のダブルクォートで囲まれた pstatic URL
        if (urls.isEmpty()) {
            val p3 = Regex("""https://$base/[^\s"'<>\\]+""")
            p3.findAll(html).forEach { m ->
                val u = m.value
                if (!urls.contains(u)) urls.add(u)
            }
        }
        return urls
    }

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
            val imgView = TranslatedImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setBackgroundColor(android.graphics.Color.parseColor("#222222"))
            }
            pageContainer.addView(imgView)
            pageViews.add(imgView)
            downloadExecutor.execute {
                if (epoch == loadEpoch) loadAndProcessPage(imgView, url, pageNum, total, epoch)
            }
            i++
        }
    }

    private fun loadAndProcessPage(view: TranslatedImageView, url: String, pageNum: Int, total: Int, epoch: Int) {
        val bitmap = downloadBitmap(url) ?: return
        if (epoch != loadEpoch) { bitmap.recycle(); return }

        mainHandler.post {
            if (epoch == loadEpoch) {
                view.setImageBitmap(bitmap)
                showStatus("$pageNum / $total ページ読込済")
            } else {
                bitmap.recycle()
            }
        }

        if (!ocrEngine.isReady) return
        ocrExecutor.execute {
            if (epoch != loadEpoch) return@execute
            val overlays = ocrEngine.processImage(bitmap)
            if (overlays.isEmpty() || epoch != loadEpoch) return@execute

            val originals = ArrayList<String>()
            var i = 0; while (i < overlays.size) { originals.add(overlays[i].originalText); i++ }

            val translations = try {
                MyMemoryTranslator.translateBatch(originals)
            } catch (t: Throwable) { originals }

            var j = 0
            while (j < overlays.size) {
                overlays[j].translatedText =
                    if (j < translations.size) translations[j] else overlays[j].originalText
                j++
            }
            mainHandler.post {
                if (epoch == loadEpoch) {
                    view.setOverlays(overlays)
                    if (pageNum == total) showStatus("")
                }
            }
        }
    }

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
        } catch (t: Throwable) {
            Log.e(TAG, "downloadBitmap: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun calcSampleSize(imgW: Int, targetW: Int): Int {
        if (imgW <= 0 || targetW <= 0) return 1
        var s = 1
        while (imgW / (s * 2) >= targetW) s *= 2
        return s
    }

    private fun showStatus(msg: String) {
        if (msg.isBlank()) {
            loadingView.visibility = View.GONE
            return
        }
        tvLoadingStatus.text = msg
        Log.d(TAG, msg)
    }

    private fun toggleNavBar() {
        navBar.visibility = if (navBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadEpoch++
        downloadExecutor.shutdown()
        ocrExecutor.shutdown()
        ocrEngine.release()
    }
}
