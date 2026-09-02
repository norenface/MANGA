package com.dqauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Runs the login + automation loop in a foreground service, in a single
 * [WebView] instance it owns for its entire lifetime -- so closing the app,
 * or switching to another one, doesn't stop it. Only running while
 * [isAutomationRunning] is true; started/stopped explicitly from the
 * "自動化を開始/停止" button (see [MainActivity]), not tied to the app's own
 * foreground/background state the way BabyCall's listener service is.
 *
 * [MainActivity] shares this same WebView (via [getWebView]) rather than
 * keeping a separate one, so there is only ever one login session: while the
 * app is visible the view is attached into the activity's layout (letting the
 * user look around or act manually between cycles), and [prepareHeadless] is
 * called when the activity gives it back up so it keeps behaving correctly
 * detached from any window.
 *
 * A bound [MainActivity] can observe live status text via [onStatusChanged]
 * and read [isAutomationRunning]/[currentStatus] to sync its UI when it
 * (re)connects to an already-running instance.
 */
class AutomationService : Service() {

    private val binder = LocalBinder()
    private lateinit var webView: WebView
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var currentStepIndex = 0
    private var awaitingNavigation = false
    private var cycleCount = 0
    private var consecutiveFailures = 0
    private var recovering = false

    private var lastStatus: String = ""
    var onStatusChanged: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AutomationService = this@AutomationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        setUpWebView()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webView = WebView(applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        prepareHeadless()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (recovering) {
                    recovering = false
                    if (running) handler.postDelayed({ runCurrentStep() }, STEP_DELAY_MS)
                } else if (awaitingNavigation) {
                    awaitingNavigation = false
                    advanceStep()
                }
            }
        }
        // Without this, a JS confirm()/alert() the site pops up before
        // submitting has nothing to render it and is silently dropped by the
        // WebView, aborting the very click that triggered it.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }
        }
    }

    fun isAutomationRunning(): Boolean = running

    fun currentStatus(): String = lastStatus

    /** The single WebView this service and [MainActivity] share -- see the
     *  class doc for how attachment is handed back and forth. */
    fun getWebView(): WebView = webView

    /**
     * Re-applies an explicit size so the WebView keeps behaving correctly
     * once it's not attached to any window (right after creation, and again
     * whenever [MainActivity] detaches it from its layout) -- some
     * WebView/Chromium internals don't run without one.
     */
    fun prepareHeadless() {
        val metrics = resources.displayMetrics
        val widthSpec = View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /** Logs the shared WebView into [id]/[password] without touching the
     *  automation loop's own state, for [MainActivity]'s login dialog to call
     *  when the user just wants to look around or confirm new credentials. */
    fun login(id: String, password: String) {
        loadLoginUrl(id, password)
    }

    fun startAutomation() {
        if (running) return
        val id = prefs.loginId
        val password = prefs.loginPassword
        if (id.isNullOrEmpty() || password.isNullOrEmpty()) return

        running = true
        currentStepIndex = 0
        cycleCount = 0
        consecutiveFailures = 0
        recovering = true
        updateStatus(getString(R.string.status_starting))
        loadLoginUrl(id, password)
    }

    fun stopAutomation() {
        running = false
        awaitingNavigation = false
        recovering = false
        consecutiveFailures = 0
        handler.removeCallbacksAndMessages(null)
        updateStatus(getString(R.string.status_stopped))
    }

    private fun loadLoginUrl(id: String, password: String) {
        val url = Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("mode", "log_in")
            .appendQueryParameter("id", id)
            .appendQueryParameter("pass", password)
            .build()
            .toString()
        webView.loadUrl(url)
    }

    private fun runCurrentStep() {
        if (!running) return
        val step = STEPS[currentStepIndex]
        updateStatus(getString(R.string.status_clicking, step.describe()))
        webView.evaluateJavascript(step.buildScript()) { rawResult ->
            if (!running) return@evaluateJavascript
            // evaluateJavascript returns a JSON-quoted string, e.g. "\"true\"".
            val result = rawResult?.trim('"')
            val skip = step.skipStepsFor(result)
            if (skip != null) {
                // This step's action wasn't needed at all (e.g. EnsureStrategy
                // finding the strategy already correct) -- move past it and
                // the steps that only make sense following its click, instead
                // of running them with nothing to act on.
                consecutiveFailures = 0
                advanceStep(skip)
            } else if (result == "true") {
                consecutiveFailures = 0
                awaitingNavigation = true
                armNavigationTimeout()
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    // Stuck on this step for too long -- likely an interstitial
                    // page (an announcement, a cooldown notice, ...) that
                    // doesn't have what we're looking for. Re-loading the login
                    // URL re-authenticates and lands back on a known-good
                    // status page, then retries the same step from there.
                    consecutiveFailures = 0
                    recovering = true
                    updateStatus(getString(R.string.status_recovering, step.describe()))
                    val id = prefs.loginId
                    val password = prefs.loginPassword
                    if (id != null && password != null) loadLoginUrl(id, password)
                } else {
                    updateStatus(getString(R.string.status_link_not_found_detail, step.describe(), describeFailure(result)))
                    handler.postDelayed({ runCurrentStep() }, RETRY_DELAY_MS)
                }
            }
        }
    }

    /**
     * Not every click is guaranteed to trigger a full page load that
     * [WebViewClient.onPageFinished] would report -- the action might be an
     * in-place AJAX update, or a JS confirm() dialog might have silently
     * swallowed it despite [WebChromeClient.onJsConfirm] auto-accepting it.
     * Without this, a click that doesn't navigate would leave
     * [awaitingNavigation] stuck true forever. If nothing has cleared the
     * flag by the time this fires, force the move to the next step anyway.
     */
    private fun armNavigationTimeout() {
        handler.postDelayed({
            if (running && awaitingNavigation) {
                awaitingNavigation = false
                advanceStep()
            }
        }, NAVIGATION_TIMEOUT_MS)
    }

    private fun advanceStep(steps: Int = 1) {
        if (!running) return
        currentStepIndex += steps
        if (currentStepIndex >= STEPS.size) {
            currentStepIndex = 0
            cycleCount++
            val intervalSeconds = prefs.cycleIntervalSeconds
            updateStatus(getString(R.string.status_cycle_wait, cycleCount, intervalSeconds))
            handler.postDelayed({ runCurrentStep() }, intervalSeconds * 1000L)
        } else {
            handler.postDelayed({ runCurrentStep() }, STEP_DELAY_MS)
        }
    }

    private fun updateStatus(text: String) {
        lastStatus = text
        onStatusChanged?.invoke(text)
    }

    /** Turns one of [AutomationStep.SelectThenClick]'s diagnostic result codes
     *  into a short Japanese phrase for the status line, so a screenshot of
     *  it pins down exactly which part of the step failed. */
    private fun describeFailure(result: String?): String {
        if (result == null) return getString(R.string.failure_reason_unknown)
        return when {
            result == "no-button" -> getString(R.string.failure_reason_no_button)
            result == "no-select" -> getString(R.string.failure_reason_no_select)
            result == "no-checkbox" -> getString(R.string.failure_reason_no_checkbox)
            result.startsWith("no-option:") ->
                getString(R.string.failure_reason_no_option, result.removePrefix("no-option:"))
            else -> getString(R.string.failure_reason_unknown)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_automation),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        handler.removeCallbacksAndMessages(null)
        onStatusChanged = null
        webView.destroy()
    }

    /** One action in the repeating cycle; each produces a JS snippet returning "true"/"false". */
    private sealed class AutomationStep {

        abstract fun describe(): String
        abstract fun buildScript(): String

        /**
         * Returns how many steps (including this one) to advance when
         * [result] indicates this step's action wasn't needed at all, or
         * null if [result] is a normal true/false/diagnostic-code outcome.
         * Only [EnsureStrategy] overrides this, to skip the steps that
         * follow it purely to act on a click it didn't end up making.
         */
        open fun skipStepsFor(result: String?): Int? = null

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
                        if (!btn) return 'no-button';

                        // Walk up from the button to the nearest ancestor that also
                        // contains a <select> -- the dropdown for this same row.
                        var container = btn.parentElement;
                        var select = null;
                        while (container && !select) {
                            select = container.querySelector('select');
                            container = container.parentElement;
                        }
                        if (!select) return 'no-select';

                        var matched = false;
                        for (var j = 0; j < select.options.length; j++) {
                            if (select.options[j].text.indexOf(optionText) !== -1) {
                                select.selectedIndex = j;
                                matched = true;
                                break;
                            }
                        }
                        if (!matched) {
                            // Include what the dropdown actually offers, so the app's
                            // status line can show it verbatim for debugging.
                            var offered = [];
                            for (var k = 0; k < select.options.length; k++) {
                                offered.push(select.options[k].text.trim());
                            }
                            return 'no-option:' + offered.join(',');
                        }

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

        /**
         * Checked on the status page: if the currently displayed 作戦 (battle
         * strategy) already contains [target], nothing needs to happen (this
         * step, plus the next [CheckboxThenClick] and [ClickText] steps meant
         * to change it, are all skipped for this cycle). Otherwise clicks the
         * button whose text contains [changeButtonText] ("作戦変更") to go
         * change it via those following steps.
         */
        data class EnsureStrategy(val target: String, val changeButtonText: String) : AutomationStep() {
            override fun describe() = "$target ($changeButtonText)"

            override fun skipStepsFor(result: String?): Int? = if (result == "skip") 3 else null

            override fun buildScript(): String {
                val quotedTarget = JSONObject.quote(target)
                val quotedButtonText = JSONObject.quote(changeButtonText)
                return """
                    (function() {
                        var target = $quotedTarget;
                        var buttonText = $quotedButtonText;

                        var btn = null;
                        var candidates = document.querySelectorAll('a, input, button');
                        for (var i = 0; i < candidates.length; i++) {
                            var el = candidates[i];
                            var text = (el.innerText || el.value || el.textContent || '').trim();
                            if (text.indexOf(buttonText) !== -1) { btn = el; break; }
                        }
                        if (!btn) return 'no-button';

                        // Walk up from the button looking for the nearest
                        // ancestor whose text names the 作戦 row, then check
                        // whether it already shows the target strategy.
                        var container = btn.parentElement;
                        var alreadySet = false;
                        for (var depth = 0; depth < 5 && container; depth++) {
                            var text = (container.innerText || container.textContent || '');
                            if (text.indexOf('作戦') !== -1) {
                                alreadySet = text.indexOf(target) !== -1;
                                break;
                            }
                            container = container.parentElement;
                        }
                        if (alreadySet) return 'skip';

                        btn.click();
                        return 'true';
                    })();
                """.trimIndent()
            }
        }

        /**
         * On the 作戦変更 (strategy change) page: selects the checkbox/radio
         * button for the row whose label is [optionText] (the site's own
         * list turned out to use round radio-style selectors, one per row of
         * a table, rather than checkboxes -- both are matched here), then
         * clicks the button whose text contains [buttonText] ("変更するぞ")
         * to submit it.
         */
        data class CheckboxThenClick(val optionText: String, val buttonText: String) : AutomationStep() {
            override fun describe() = "$optionText / $buttonText"

            override fun buildScript(): String {
                val quotedOptionText = JSONObject.quote(optionText)
                val quotedButtonText = JSONObject.quote(buttonText)
                return """
                    (function() {
                        var optionText = $quotedOptionText;
                        var buttonText = $quotedButtonText;

                        // Find the option's own label element first (rather
                        // than starting from every radio/checkbox on the page
                        // and asking "does some ancestor's text contain
                        // optionText?") -- with many rows sharing the same
                        // list container, that ancestor check goes broad
                        // enough to match almost any row well before it
                        // narrows down to the right one, so it was grabbing
                        // the first checkbox in the whole list instead of
                        // this option's. An element whose OWN trimmed text
                        // exactly equals optionText stays scoped to just this
                        // row no matter how big the list is.
                        var label = null;
                        var allEls = document.querySelectorAll('*');
                        for (var i = 0; i < allEls.length; i++) {
                            if ((allEls[i].textContent || '').trim() === optionText) { label = allEls[i]; break; }
                        }
                        if (!label) {
                            for (var i2 = 0; i2 < allEls.length; i2++) {
                                var el2 = allEls[i2];
                                if (el2.children.length === 0 && (el2.textContent || '').indexOf(optionText) !== -1) {
                                    label = el2;
                                    break;
                                }
                            }
                        }
                        if (!label) return 'no-checkbox';

                        var row = label.closest('tr');
                        var checkbox = row ? row.querySelector('input[type="checkbox"], input[type="radio"]') : null;
                        if (!checkbox) {
                            var container = label;
                            for (var depth = 0; depth < 3 && !checkbox; depth++) {
                                container = container.parentElement;
                                if (!container) break;
                                checkbox = container.querySelector('input[type="checkbox"], input[type="radio"]');
                            }
                        }
                        if (!checkbox) return 'no-checkbox';

                        checkbox.checked = true;
                        checkbox.dispatchEvent(new Event('change', { bubbles: true }));
                        checkbox.dispatchEvent(new Event('click', { bubbles: true }));

                        var btn = null;
                        var candidates = document.querySelectorAll('a, input, button');
                        for (var j = 0; j < candidates.length; j++) {
                            var el = candidates[j];
                            var text = (el.innerText || el.value || el.textContent || '').trim();
                            if (text.indexOf(buttonText) !== -1) { btn = el; break; }
                        }
                        if (!btn) return 'no-button';

                        btn.click();
                        return 'true';
                    })();
                """.trimIndent()
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://app.h3z.jp/games/dqa5/dqadventure5.cgi"
        private const val CHANNEL_ID = "dqauto_automation"
        private const val NOTIFICATION_ID = 2001

        // The status page's シングルバトル row: a dropdown of opponents (choose
        // the one containing "アレフガルド") next to a 「モンスターをしばく」
        // button that submits it, then a 「ステータス」 link to come back.
        // Once back on the status page, EnsureStrategy checks 作戦 (battle
        // strategy) and, only if it isn't already "どとうのひつじ", clicks
        // 「作戦変更」 -- CheckboxThenClick and the final ClickText only run in
        // that case (see AutomationStep.EnsureStrategy.skipStepsFor).
        // Update these if they don't match the site's actual wording.
        private val STEPS: List<AutomationStep> = listOf(
            AutomationStep.SelectThenClick(optionText = "アレフガルド", buttonText = "モンスターをしばく"),
            AutomationStep.ClickText("ステータス"),
            AutomationStep.EnsureStrategy(target = "どとうのひつじ", changeButtonText = "作戦変更"),
            AutomationStep.CheckboxThenClick(optionText = "どとうのひつじ", buttonText = "変更するぞ"),
            AutomationStep.ClickText("ええがな")
        )

        private const val STEP_DELAY_MS = 1500L
        private const val RETRY_DELAY_MS = 2000L
        private const val NAVIGATION_TIMEOUT_MS = 8000L
        private const val MAX_CONSECUTIVE_FAILURES = 8

        fun start(context: Context) {
            val intent = Intent(context, AutomationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }
}
