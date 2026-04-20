package com.manga.translator.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.manga.translator.api.NaverComicsApi
import com.manga.translator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentEpisode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupNavigationButtons()
        loadMangaList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false

                    // エピソード詳細ページはReaderActivityで開く
                    if (url.contains("webtoon/detail")) {
                        val episodeNo = extractEpisodeNo(url)
                        if (episodeNo > 0) {
                            openReader(episodeNo)
                            return true
                        }
                    }

                    // 翻訳プロキシのURLに変換してWebViewで読み込む
                    val translatedUrl = NaverComicsApi.toTranslatedUrl(url)
                    if (translatedUrl != url) {
                        view?.loadUrl(translatedUrl)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectCustomStyles()
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        binding.etEpisodeNumber.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val episodeNo = binding.etEpisodeNumber.text.toString().toIntOrNull() ?: 0
                if (episodeNo > 0) {
                    openReader(episodeNo)
                }
                true
            } else false
        }
    }

    private fun setupNavigationButtons() {
        binding.btnPrevEpisode.setOnClickListener {
            if (currentEpisode > 1) {
                currentEpisode--
                openReader(currentEpisode)
            }
        }

        binding.btnNextEpisode.setOnClickListener {
            currentEpisode++
            openReader(currentEpisode)
        }

        binding.btnOpenList.setOnClickListener {
            loadMangaList()
        }
    }

    private fun loadMangaList() {
        val url = NaverComicsApi.getTranslatedListUrl()
        binding.webView.loadUrl(url)
    }

    private fun injectCustomStyles() {
        val css = """
            javascript:(function() {
                var style = document.createElement('style');
                style.textContent = `
                    * { font-family: 'Noto Sans JP', sans-serif !important; }
                    .ComicChapterItem { cursor: pointer; }
                `;
                document.head.appendChild(style);
            })()
        """.trimIndent()
        binding.webView.loadUrl(css)
    }

    private fun extractEpisodeNo(url: String): Int {
        return try {
            val regex = Regex("[?&]no=(\\d+)")
            regex.find(url)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun openReader(episodeNo: Int) {
        currentEpisode = episodeNo
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra(ReaderActivity.EXTRA_EPISODE_NO, episodeNo)
        }
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
