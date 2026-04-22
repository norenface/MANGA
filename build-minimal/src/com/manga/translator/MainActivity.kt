package com.manga.translator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var etEpisode: EditText
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private var currentEpisode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView) as WebView
        etEpisode = findViewById(R.id.etEpisode) as EditText
        btnPrev = findViewById(R.id.btnPrev) as Button
        btnNext = findViewById(R.id.btnNext) as Button

        setupWebView()
        setupControls()
        webView.loadUrl(Constants.episodeListUrl())
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
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.contains("webtoon/detail")) {
                    val no = extractNo(url)
                    if (no > 0) {
                        openReader(no)
                        return true
                    }
                }
                val translated = Constants.toTranslated(url)
                if (translated != url) {
                    view.loadUrl(translated)
                    return true
                }
                return false
            }
        })
    }

    private fun setupControls() {
        btnPrev.setOnClickListener {
            if (currentEpisode > 1) openReader(--currentEpisode)
        }
        btnNext.setOnClickListener {
            openReader(++currentEpisode)
        }
        etEpisode.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER
                    && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val no = etEpisode.text.toString().toIntOrNull() ?: 0
                if (no > 0) openReader(no)
                true
            } else false
        }
    }

    private fun openReader(no: Int) {
        currentEpisode = no
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(ReaderActivity.EXTRA_EPISODE, no)
        startActivity(intent)
    }

    private fun extractNo(url: String): Int = try {
        Regex("[?&]no=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 0
    } catch (e: Exception) { 0 }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
