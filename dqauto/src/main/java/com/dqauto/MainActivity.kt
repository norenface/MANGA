package com.dqauto

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dqauto.databinding.ActivityMainBinding
import com.dqauto.databinding.DialogLoginBinding
import org.json.JSONObject

/**
 * Logs into the browser game with a locally-stored ID/password and then
 * repeatedly clicks through a fixed sequence of text links -- シングルバトル
 * (single battle) -> アレフガルド (Alefgard selection) -> ステータス (back to
 * status) -- waiting for each page to finish loading before clicking the
 * next, then pausing [Prefs.cycleIntervalSeconds] before starting the next
 * cycle. Only runs while this screen is visible; backgrounding the app
 * pauses it (see [onPause]/[onResume]).
 *
 * The [STEPS] link-text substrings were provided by the user, not verified
 * against the live site (this environment's network policy blocks fetching
 * it) -- if a step's link isn't found, the status line will say so and keep
 * retrying, which is the signal to double check the exact wording on the
 * actual page and adjust [STEPS] accordingly.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var currentStepIndex = 0
    private var awaitingNavigation = false
    private var cycleCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (awaitingNavigation) {
                    awaitingNavigation = false
                    advanceStep()
                }
            }
        }

        binding.btnLoginSettings.setOnClickListener { showLoginDialog() }
        binding.btnToggleAutomation.setOnClickListener { toggleAutomation() }

        if (prefs.hasLogin) {
            loadLoginUrl()
        } else {
            showLoginDialog()
        }
    }

    private fun loadLoginUrl() {
        val id = prefs.loginId ?: return
        val password = prefs.loginPassword ?: return
        val url = Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("mode", "log_in")
            .appendQueryParameter("id", id)
            .appendQueryParameter("pass", password)
            .build()
            .toString()
        binding.webView.loadUrl(url)
    }

    private fun showLoginDialog() {
        val dialogBinding = DialogLoginBinding.inflate(layoutInflater)
        dialogBinding.etLoginId.setText(prefs.loginId.orEmpty())
        dialogBinding.etLoginPassword.setText(prefs.loginPassword.orEmpty())

        AlertDialog.Builder(this)
            .setTitle(R.string.login_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.button_login) { _, _ ->
                val id = dialogBinding.etLoginId.text?.toString()?.trim().orEmpty()
                val password = dialogBinding.etLoginPassword.text?.toString()?.trim().orEmpty()
                if (id.isNotEmpty() && password.isNotEmpty()) {
                    prefs.loginId = id
                    prefs.loginPassword = password
                    loadLoginUrl()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun toggleAutomation() {
        if (running) stopAutomation() else startAutomation()
    }

    private fun startAutomation() {
        if (!prefs.hasLogin) {
            showLoginDialog()
            return
        }
        running = true
        currentStepIndex = 0
        binding.btnToggleAutomation.setText(R.string.button_stop_automation)
        setStatus(getString(R.string.status_starting))
        clickCurrentStep()
    }

    private fun stopAutomation() {
        running = false
        awaitingNavigation = false
        handler.removeCallbacksAndMessages(null)
        binding.btnToggleAutomation.setText(R.string.button_start_automation)
        setStatus(getString(R.string.status_stopped))
    }

    private fun clickCurrentStep() {
        if (!running) return
        val targetText = STEPS[currentStepIndex]
        setStatus(getString(R.string.status_clicking, targetText))
        binding.webView.evaluateJavascript(buildClickScript(targetText)) { result ->
            if (!running) return@evaluateJavascript
            if (result == "true") {
                awaitingNavigation = true
            } else {
                setStatus(getString(R.string.status_link_not_found, targetText))
                handler.postDelayed({ clickCurrentStep() }, RETRY_DELAY_MS)
            }
        }
    }

    private fun advanceStep() {
        if (!running) return
        currentStepIndex++
        if (currentStepIndex >= STEPS.size) {
            currentStepIndex = 0
            cycleCount++
            val intervalSeconds = prefs.cycleIntervalSeconds
            setStatus(getString(R.string.status_cycle_wait, cycleCount, intervalSeconds))
            handler.postDelayed({ clickCurrentStep() }, intervalSeconds * 1000L)
        } else {
            handler.postDelayed({ clickCurrentStep() }, STEP_DELAY_MS)
        }
    }

    /** Finds the first link/button whose visible text contains [targetText] and clicks it. */
    private fun buildClickScript(targetText: String): String {
        val quotedTarget = JSONObject.quote(targetText)
        return """
            (function() {
                var target = $quotedTarget;
                var els = document.querySelectorAll('a, input[type="submit"], input[type="button"], button');
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var text = (el.innerText || el.value || el.textContent || '').trim();
                    if (text.indexOf(target) !== -1) {
                        el.click();
                        return true;
                    }
                }
                return false;
            })();
        """.trimIndent()
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    override fun onPause() {
        super.onPause()
        // Stops scheduling further steps, but leaves `running`/currentStepIndex
        // alone so onResume can just pick back up from where it left off.
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        if (running && !awaitingNavigation) {
            clickCurrentStep()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
    }

    companion object {
        private const val BASE_URL = "https://app.h3z.jp/games/dqa5/dqadventure5.cgi"

        // Substrings searched for within visible link/button text, in the order
        // they're clicked each cycle. Update these if they don't match the
        // site's actual wording -- see the class doc comment.
        private val STEPS = listOf("シングルバトル", "アレフガルド", "ステータス")

        private const val STEP_DELAY_MS = 1500L
        private const val RETRY_DELAY_MS = 2000L
    }
}
