package com.manga.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.manga.translator.ocr.TesseractOcrEngine
import com.manga.translator.translation.MyMemoryTranslator
import com.manga.translator.ui.TranslatedImageView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_EPISODE = "episode_no"
        private const val TAG = "ReaderActivity"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var ocrEngine: TesseractOcrEngine

    // 画像ダウンロード用 (最大3並列)
    private val downloadExecutor = Executors.newFixedThreadPool(3)
    // OCR処理は直列 (TessBaseAPI はスレッドアンセーフのため)
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
    private lateinit var extractorWebView: WebView

    private var episodeNo = 1
    private val pageViews = ArrayList<TranslatedImageView>()
    private var overlaysVisible = true
    private var urlsCallbackRegistered = false

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

        setupExtractorWebView()
        ocrEngine = TesseractOcrEngine(this)

        setupControls()
        initTesseract()
        loadEpisode(episodeNo)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupExtractorWebView() {
        extractorWebView = WebView(this)
        extractorWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0 Mobile Safari/537.36"
        }
        // JavascriptInterface は一度だけ登録する
        extractorWebView.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun onUrls(json: String) {
                try {
                    val arr = JSONArray(json)
                    val urls = ArrayList<String>()
                    var i = 0
                    while (i < arr.length()) { urls.add(arr.getString(i)); i++ }
                    if (urls.isNotEmpty()) {
                        mainHandler.post { startLoadingPages(urls) }
                    } else {
                        mainHandler.post { updateStatus("画像URLが見つかりませんでした") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "URL parse error: ${e.message}")
                }
            }
        }, "Android")

        extractorWebView.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.postDelayed({
                    val js = "javascript:(function(){" +
                        "var found=[];" +
                        "function add(s){document.querySelectorAll(s).forEach(function(img){" +
                        "  var src=img.src||img.getAttribute('data-src')||'';" +
                        "  if(src.indexOf('image-comic.pstatic.net')>-1&&found.indexOf(src)<0)found.push(src);" +
                        "})}" +
                        "add('img[src*=\"image-comic.pstatic.net\"]');" +
                        "add('#comic_view_area img');add('.viewer_lst img');add('._img');" +
                        "if(found.length===0){document.querySelectorAll('img').forEach(function(img){" +
                        "  if((img.naturalWidth>300||img.width>300)&&img.src&&img.src.indexOf('http')===0&&found.indexOf(img.src)<0)found.push(img.src);" +
                        "})}" +
                        "Android.onUrls(JSON.stringify(found));" +
                        "})()"
                    view.loadUrl(js)
                }, 2500)
            }
        })
    }

    private fun initTesseract() {
        updateStatus("Tesseract モデルを初期化中…")
        Thread {
            val ok = ocrEngine.initialize()
            mainHandler.post {
                if (ok) {
                    Log.i(TAG, "Tesseract ready")
                } else {
                    updateStatus("OCRモデルの初期化に失敗しました")
                }
            }
        }.start()
    }

    private fun setupControls() {
        btnClose.setOnClickListener { finish() }
        btnPrev.setOnClickListener { if (episodeNo > 1) loadEpisode(--episodeNo) }
        btnNext.setOnClickListener { loadEpisode(++episodeNo) }
        btnToggleOcr.setOnClickListener {
            overlaysVisible = !overlaysVisible
            var i = 0
            while (i < pageViews.size) { pageViews[i].toggleOverlays(); i++ }
            btnToggleOcr.text = if (overlaysVisible) "翻訳非表示" else "翻訳表示"
        }
        scrollView.setOnClickListener { toggleNavBar() }
    }

    private fun loadEpisode(no: Int) {
        episodeNo = no
        tvTitle.text = "第${no}話"
        pageViews.clear()
        pageContainer.removeAllViews()
        loadingView.visibility = View.VISIBLE
        scrollView.visibility = View.GONE
        btnToggleOcr.visibility = View.GONE
        updateStatus("エピソード ${no} の画像URLを取得中…")
        extractorWebView.loadUrl(NaverComicsApi.translatedEpisodeUrl(no))
    }

    private fun startLoadingPages(urls: ArrayList<String>) {
        loadingView.visibility = View.GONE
        scrollView.visibility = View.VISIBLE
        btnToggleOcr.visibility = View.VISIBLE
        updateStatus("${urls.size} ページを読み込み中…")

        var i = 0
        while (i < urls.size) {
            val idx = i
            val url = urls[idx]
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

            val pageNum = idx + 1
            val total = urls.size
            downloadExecutor.execute {
                loadAndProcessPage(imgView, url, pageNum, total)
            }
            i++
        }
    }

    private fun loadAndProcessPage(view: TranslatedImageView, url: String, pageNum: Int, total: Int) {
        val bitmap = downloadBitmap(url) ?: return
        mainHandler.post {
            view.setImageBitmap(bitmap)
            updateStatus("OCR処理中… ($pageNum/$total)")
        }

        if (!ocrEngine.isReady) return

        // OCR は直列実行キューに投入 (同時アクセス防止)
        ocrExecutor.execute {
            val overlays = ocrEngine.processImage(bitmap)
            if (overlays.isEmpty()) return@execute

            val originals = ArrayList<String>()
            var i = 0
            while (i < overlays.size) { originals.add(overlays[i].originalText); i++ }

            val translations = try {
                MyMemoryTranslator.translateBatch(originals)
            } catch (e: Exception) {
                originals
            }
            var j = 0
            while (j < overlays.size) {
                overlays[j].translatedText = if (j < translations.size) translations[j] else overlays[j].originalText
                j++
            }

            mainHandler.post {
                view.setOverlays(overlays)
                if (pageNum == total) updateStatus("")
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Referer", "https://comic.naver.com")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            conn.connect()
            BitmapFactory.decodeStream(conn.inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Image download failed for $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateStatus(msg: String) {
        if (msg.isBlank()) return
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
        downloadExecutor.shutdown()
        ocrExecutor.shutdown()
        ocrEngine.release()
        extractorWebView.destroy()
    }
}
