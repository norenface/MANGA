package com.manga.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

class ReaderActivity : Activity() {

    companion object {
        const val EXTRA_EPISODE = "episode_no"
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var navBar: View
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnOcr: Button
    private var episodeNo = 1
    private var ocrInjected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        episodeNo = intent.getIntExtra(EXTRA_EPISODE, 1)

        webView  = findViewById(R.id.webView) as WebView
        progress = findViewById(R.id.progress) as ProgressBar
        navBar   = findViewById(R.id.navBar)
        tvTitle  = findViewById(R.id.tvTitle) as TextView
        btnClose = findViewById(R.id.btnClose) as Button
        btnPrev  = findViewById(R.id.btnPrev) as Button
        btnNext  = findViewById(R.id.btnNext) as Button
        btnOcr   = findViewById(R.id.btnOcr) as Button

        setupWebView()
        setupControls()
        loadEpisode(episodeNo)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.setWebViewClient(object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                ocrInjected = false
                btnOcr.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                val hideCss = "javascript:(function(){" +
                    "var s=document.createElement('style');" +
                    "s.textContent='header,nav,.HeaderArea,.AdArea,.ad_area,footer,.Footer{display:none!important;}'" +
                    "+'body{background:#111!important;margin:0!important;}'" +
                    "+'#comic_view_area img,._img,.viewer_lst img{width:100%!important;max-width:100%!important;}';" +
                    "document.head.appendChild(s);})()"
                view.loadUrl(hideCss)
                progress.visibility = View.GONE

                if (url.contains("detail")) {
                    btnOcr.visibility = View.VISIBLE
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.contains("webtoon/detail")) {
                    val no = extractNo(url)
                    if (no > 0 && no != episodeNo) {
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
        })

        webView.setOnClickListener { toggleNav() }
    }

    private fun setupControls() {
        btnClose.setOnClickListener { finish() }
        btnPrev.setOnClickListener {
            if (episodeNo > 1) loadEpisode(--episodeNo)
        }
        btnNext.setOnClickListener {
            loadEpisode(++episodeNo)
        }
        btnOcr.setOnClickListener {
            runOcrOverlay()
        }
    }

    private fun loadEpisode(no: Int) {
        episodeNo = no
        tvTitle.text = "第${no}話"
        ocrInjected = false
        btnOcr.visibility = View.GONE
        webView.loadUrl(Constants.episodeUrl(no))
    }

    private fun runOcrOverlay() {
        if (!ocrInjected) {
            webView.evaluateJavascript(Constants.OCR_OVERLAY_JS, null)
            ocrInjected = true
        }
        webView.evaluateJavascript("javascript:window.runOcrOnPage();", null)
        btnOcr.text = "OCR処理中…"
        btnOcr.isEnabled = false
        webView.postDelayed({
            btnOcr.text = "OCR翻訳"
            btnOcr.isEnabled = true
        }, 30000)
    }

    private fun toggleNav() {
        navBar.visibility =
            if (navBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun extractNo(url: String): Int = try {
        Regex("[?&]no=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
    } catch (e: Exception) { 0 }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
