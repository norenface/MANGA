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
 * repeatedly performs a fixed sequence of steps on the status page --
 * pick "アレフガルド" in the シングルバトル dropdown and press 「モンスターを
 * しばく」, then click the 「ステータス」 link to come back -- waiting for
 * each page to finish loading before doing the next step, then pausing
 * [Prefs.cycleIntervalSeconds] before starting the next cycle. Only runs
 * while this screen is visible; backgrounding the app pauses it (see
 * [onPause]/[onResume]).
 *
 * The [STEPS] text used to find the right dropdown option/buttons/links was
 * described by the user, not verified against the live site directly (this
 * environment's network policy blocks fetching it) -- if a step doesn't
 * find its target, the status line will say so and keep retrying, which is
 * the signal to double check the exact wording on the actual page and
 * adjust [STEPS] accordingly.
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
        runCurrentStep()
    }

    private fun stopAutomation() {
        running = false
        awaitingNavigation = false
        handler.removeCallbacksAndMessages(null)
        binding.btnToggleAutomation.setText(R.string.button_start_automation)
        setStatus(getString(R.string.status_stopped))
    }

    private fun runCurrentStep() {
        if (!running) return
        val step = STEPS[currentStepIndex]
        setStatus(getString(R.string.status_clicking, step.describe()))
        binding.webView.evaluateJavascript(step.buildScript()) { rawResult ->
            if (!running) return@evaluateJavascript
            // evaluateJavascript returns a JSON-quoted string, e.g. "\"true\"".
            val result = rawResult?.trim('"')
            if (result == "true") {
                awaitingNavigation = true
            } else {
                setStatus(getString(R.string.status_link_not_found, step.describe()))
                handler.postDelayed({ runCurrentStep() }, RETRY_DELAY_MS)
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
            handler.postDelayed({ runCurrentStep() }, intervalSeconds * 1000L)
        } else {
            handler.postDelayed({ runCurrentStep() }, STEP_DELAY_MS)
        }
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
            runCurrentStep()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
    }

    /** One action in the repeating cycle; each produces a JS snippet returning "true"/"false". */
    private sealed class AutomationStep {

        abstract fun describe(): String
        abstract fun buildScript(): String

        /**
         * Picks the option containing [optionText] in the `<select>` nearest to
         * (an ancestor of, or preceding) the button whose text contains
         * [buttonText], then clicks that button -- the シングルバトル row's
         * "opponent" dropdown plus its 「モンスターをしばく」 submit button.
         */
        data class SelectThenClick(val optionText: String, val buttonText: String) : AutomationStep() {
            override fun describe() = "$optionText / $buttonText"

            override fun buildScript(): String {
                val quotedButtonText = JSONObject.quote(buttonText)
                val quotedOptionText = JSONObject.quote(optionText)
                return """
                    (function() {
                        var buttonText = $quotedButtonText;
                        var optionText = $quotedOptionText;

                        var btn = null;
                        var candidates = document.querySelectorAll('a, input, button');
                        for (var i = 0; i < candidates.length; i++) {
                            var el = candidates[i];
                            var text = (el.innerText || el.value || el.textContent || '').trim();
                            if (text.indexOf(buttonText) !== -1) { btn = el; break; }
                        }
                        if (!btn) return 'false';

                        // Walk up from the button to the nearest ancestor that also
                        // contains a <select> -- the dropdown for this same row.
                        var container = btn.parentElement;
                        var select = null;
                        while (container && !select) {
                            select = container.querySelector('select');
                            container = container.parentElement;
                        }
                        if (!select) return 'false';

                        var matched = false;
                        for (var j = 0; j < select.options.length; j++) {
                            if (select.options[j].text.indexOf(optionText) !== -1) {
                                select.selectedIndex = j;
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) return 'false';

                        select.dispatchEvent(new Event('change', { bubbles: true }));
                        btn.click();
                        return 'true';
                    })();
                """.trimIndent()
            }
        }

        /** Finds the first link/button whose visible text contains [text] and clicks it. */
        data class ClickText(val text: String) : AutomationStep() {
            override fun describe() = text

            override fun buildScript(): String {
                val quotedText = JSONObject.quote(text)
                return """
                    (function() {
                        var target = $quotedText;
                        var els = document.querySelectorAll('a, input[type="submit"], input[type="button"], button');
                        for (var i = 0; i < els.length; i++) {
                            var el = els[i];
                            var text = (el.innerText || el.value || el.textContent || '').trim();
                            if (text.indexOf(target) !== -1) {
                                el.click();
                                return 'true';
                            }
                        }
                        return 'false';
                    })();
                """.trimIndent()
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://app.h3z.jp/games/dqa5/dqadventure5.cgi"

        // The status page's シングルバトル row: a dropdown of opponents (choose
        // the one containing "アレフガルド") next to a 「モンスターをしばく」
        // button that submits it, then a 「ステータス」 link to come back.
        // Update these if they don't match the site's actual wording -- see
        // the class doc comment.
        private val STEPS: List<AutomationStep> = listOf(
            AutomationStep.SelectThenClick(optionText = "アレフガルド", buttonText = "モンスターをしばく"),
            AutomationStep.ClickText("ステータス")
        )

        private const val STEP_DELAY_MS = 1500L
        private const val RETRY_DELAY_MS = 2000L
    }
}
