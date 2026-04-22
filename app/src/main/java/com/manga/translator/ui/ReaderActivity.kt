package com.manga.translator.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translator.R
import com.manga.translator.adapter.MangaPageAdapter
import com.manga.translator.api.NaverComicsApi
import com.manga.translator.databinding.ActivityReaderBinding
import com.manga.translator.ocr.OcrTranslationEngine
import com.manga.translator.ui.widget.TranslatedImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EPISODE_NO = "episode_no"
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var ocrEngine: OcrTranslationEngine
    private lateinit var pageAdapter: MangaPageAdapter
    private var episodeNo = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        episodeNo = intent.getIntExtra(EXTRA_EPISODE_NO, 1)

        setupOcr()
        setupRecyclerView()
        setupWebView()
        setupControls()
        loadEpisode(episodeNo)
    }

    private fun setupOcr() {
        ocrEngine = OcrTranslationEngine()
        showStatus("翻訳モデルをダウンロード中…")

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ocrEngine.initialize() }
            showStatus(if (ok) "OCR翻訳準備完了 ✓" else "モデル取得失敗 (Wi-Fi接続を確認してください)")
            binding.tvModelStatus.postDelayed({
                binding.tvModelStatus.visibility = View.GONE
            }, 3000)
        }
    }

    private fun showStatus(msg: String) {
        binding.tvModelStatus.text = msg
        binding.tvModelStatus.visibility = View.VISIBLE
    }

    private fun setupRecyclerView() {
        pageAdapter = MangaPageAdapter(ocrEngine, lifecycleScope)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            adapter = pageAdapter
            setHasFixedSize(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onImagesFound(jsonUrls: String) {
                    try {
                        val arr = JSONArray(jsonUrls)
                        val urls = (0 until arr.length()).map { arr.getString(it) }
                        if (urls.isNotEmpty()) {
                            runOnUiThread { switchToReader(urls) }
                        }
                    } catch (ignored: Exception) { }
                }
            }, "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Wait 2s for lazy-loaded images, then extract URLs
                    view?.postDelayed({ injectImageExtractor() }, 2000)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.contains("webtoon/detail")) {
                        val no = extractEpisodeNo(url)
                        if (no > 0 && no != episodeNo) {
                            episodeNo = no
                            loadEpisode(no)
                            return true
                        }
                    }
                    if (url.contains("webtoon/list")) {
                        finish()
                        return true
                    }
                    return false
                }
            }
        }
    }

    private fun injectImageExtractor() {
        val js = """
            javascript:(function(){
                var found=[];
                function collect(sel){
                    document.querySelectorAll(sel).forEach(function(img){
                        var src=img.src||img.getAttribute('data-src')||'';
                        if(src.startsWith('http')&&found.indexOf(src)<0
                            &&src.indexOf('blank')<0&&src.indexOf('loading')<0
                            &&src.indexOf('icon')<0&&src.indexOf('logo')<0){
                            found.push(src);
                        }
                    });
                }
                collect('img[src*="image-comic.pstatic.net"]');
                collect('#comic_view_area img');
                collect('.viewer_lst img');
                collect('._img');
                collect('.wt_viewer img');
                collect('[class*="viewer"] img');
                // Fallback: wide images
                if(found.length===0){
                    document.querySelectorAll('img').forEach(function(img){
                        if((img.naturalWidth>300||img.width>300)&&img.src.startsWith('http')){
                            if(found.indexOf(img.src)<0) found.push(img.src);
                        }
                    });
                }
                Android.onImagesFound(JSON.stringify(found));
            })()
        """.trimIndent()
        binding.webView.loadUrl(js)
    }

    private fun switchToReader(urls: List<String>) {
        binding.progressBar.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.btnToggleOcr.visibility = View.VISIBLE
        pageAdapter.setUrls(urls)
    }

    private fun setupControls() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnPrevEpisode.setOnClickListener {
            if (episodeNo > 1) loadEpisode(--episodeNo)
        }

        binding.btnNextEpisode.setOnClickListener {
            loadEpisode(++episodeNo)
        }

        binding.btnToggleOcr.setOnClickListener {
            toggleAllOverlays()
        }

        // Tap RecyclerView to show/hide nav bar
        binding.recyclerView.setOnClickListener { toggleNavBar() }
    }

    private fun toggleAllOverlays() {
        val lm = binding.recyclerView.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        for (i in first..last) {
            val vh = binding.recyclerView.findViewHolderForAdapterPosition(i)
            vh?.itemView?.findViewById<TranslatedImageView>(R.id.translatedImageView)
                ?.toggleOverlays()
        }
    }

    private fun loadEpisode(no: Int) {
        episodeNo = no
        binding.tvEpisodeTitle.text = "第 $no 話"
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.btnToggleOcr.visibility = View.GONE
        pageAdapter.setUrls(emptyList())
        binding.webView.loadUrl(NaverComicsApi.getTranslatedEpisodeUrl(no))
    }

    private fun toggleNavBar() {
        binding.navBar.visibility =
            if (binding.navBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun extractEpisodeNo(url: String): Int = try {
        Regex("[?&]no=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
    } catch (_: Exception) { 0 }

    override fun onBackPressed() {
        if (binding.recyclerView.visibility == View.VISIBLE) {
            finish()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrEngine.release()
    }
}
