package com.manga.translator.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.manga.translator.api.NaverComicsApi
import com.manga.translator.databinding.ActivityReaderBinding

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EPISODE_NO = "episode_no"
    }

    private lateinit var binding: ActivityReaderBinding
    private var episodeNo = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        episodeNo = intent.getIntExtra(EXTRA_EPISODE_NO, 1)

        setupWebView()
        setupControls()
        loadEpisode(episodeNo)

        binding.tvEpisodeTitle.text = "第 $episodeNo 話"
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
                mediaPlaybackRequiresUserGesture = false
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

                    // 別エピソードへのナビゲーション
                    if (url.contains("webtoon/detail")) {
                        val no = extractEpisodeNo(url)
                        if (no > 0) {
                            episodeNo = no
                            loadEpisode(no)
                            return true
                        }
                    }

                    // エピソード一覧に戻る
                    if (url.contains("webtoon/list")) {
                        finish()
                        return true
                    }

                    val translatedUrl = NaverComicsApi.toTranslatedUrl(url)
                    if (translatedUrl != url) {
                        view?.loadUrl(translatedUrl)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    injectReaderStyles()
                    updateEpisodeTitle()
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    binding.progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupControls() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnPrevEpisode.setOnClickListener {
            if (episodeNo > 1) {
                episodeNo--
                loadEpisode(episodeNo)
            }
        }

        binding.btnNextEpisode.setOnClickListener {
            episodeNo++
            loadEpisode(episodeNo)
        }

        // タップでナビバーの表示/非表示を切り替え
        binding.webView.setOnClickListener {
            toggleNavBar()
        }
    }

    private fun loadEpisode(no: Int) {
        val url = NaverComicsApi.getTranslatedEpisodeUrl(no)
        binding.webView.loadUrl(url)
        binding.tvEpisodeTitle.text = "第 $no 話"
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun updateEpisodeTitle() {
        binding.tvEpisodeTitle.text = "第 $episodeNo 話"
    }

    private fun injectReaderStyles() {
        // ヘッダー・フッターを非表示にしてマンガ画像を全画面表示
        val js = """
            javascript:(function() {
                var style = document.createElement('style');
                style.textContent = `
                    header, .HeaderArea, nav, .Navigation, footer, .Footer,
                    .ComicViewerBanner, .ad_area, .AdArea {
                        display: none !important;
                    }
                    body {
                        margin: 0 !important;
                        padding: 0 !important;
                        background: #1a1a1a !important;
                    }
                    .viewer_lst, .ImageViewArea, .comic_viewer,
                    ._img, img[src*='comic.naver'] {
                        width: 100% !important;
                        max-width: 100% !important;
                        display: block !important;
                    }
                `;
                document.head.appendChild(style);
            })()
        """.trimIndent()
        binding.webView.loadUrl(js)
    }

    private fun toggleNavBar() {
        val navBar = binding.navBar
        if (navBar.visibility == View.VISIBLE) {
            navBar.visibility = View.GONE
        } else {
            navBar.visibility = View.VISIBLE
        }
    }

    private fun extractEpisodeNo(url: String): Int {
        return try {
            val regex = Regex("[?&]no=(\\d+)")
            regex.find(url)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
