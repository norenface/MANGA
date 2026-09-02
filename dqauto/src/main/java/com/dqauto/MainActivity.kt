package com.dqauto

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dqauto.databinding.ActivityMainBinding
import com.dqauto.databinding.DialogLoginBinding

/**
 * This screen's own [WebView] is only for logging in and letting the
 * caregiver look around manually -- the actual automation loop runs
 * independently in [AutomationService] (its own separate WebView/session),
 * so it keeps going after this screen is closed or the app is backgrounded.
 * "自動化を開始/停止" starts/stops that service; while bound to it, this
 * screen mirrors its live status text and running state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var automationService: AutomationService? = null

    /** Set when "start" was tapped before the service finished (re)binding;
     *  consumed by [onServiceConnected] as soon as it does. */
    private var pendingStart = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as AutomationService.LocalBinder).getService()
            automationService = bound
            bound.onStatusChanged = { text -> runOnUiThread { setStatus(text) } }
            bound.onScreenshotChanged = { bitmap -> runOnUiThread { binding.ivAutomationPreview.setImageBitmap(bitmap) } }
            if (pendingStart) {
                pendingStart = false
                bound.startAutomation()
            }
            setStatus(bound.currentStatus())
            bound.currentScreenshot()?.let { binding.ivAutomationPreview.setImageBitmap(it) }
            updateToggleButton(bound.isAutomationRunning())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            automationService?.onStatusChanged = null
            automationService?.onScreenshotChanged = null
            automationService = null
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = WebViewClient()

        binding.btnLoginSettings.setOnClickListener { showLoginDialog() }
        binding.btnToggleAutomation.setOnClickListener { toggleAutomation() }

        // Only log in here when credentials are first entered via the dialog
        // below (which loads the URL itself right after saving them) -- NOT
        // on every app open. This screen's WebView shares its cookies with
        // AutomationService's, so re-logging in here while the background
        // automation is mid-cycle can invalidate/replace its session out from
        // under it. The automation preview panel shows its live state instead.
        if (!prefs.hasLogin) {
            showLoginDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AutomationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        automationService?.onStatusChanged = null
        automationService?.onScreenshotChanged = null
        unbindService(serviceConnection)
        automationService = null
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
        val service = automationService
        if (service != null && service.isAutomationRunning()) {
            service.stopAutomation()
            AutomationService.stop(this)
            updateToggleButton(false)
        } else {
            if (!prefs.hasLogin) {
                showLoginDialog()
                return
            }
            // onStart() already binds, so the service is normally already
            // connected by the time this button is reachable; startAutomation()
            // just runs immediately. On the rare chance it isn't yet, starting
            // the service here ensures it exists, and pendingStart has
            // onServiceConnected call startAutomation() as soon as it binds.
            AutomationService.start(this)
            if (service != null) {
                service.startAutomation()
                updateToggleButton(true)
            } else {
                pendingStart = true
                setStatus(getString(R.string.status_starting))
            }
        }
    }

    private fun updateToggleButton(running: Boolean) {
        binding.btnToggleAutomation.setText(
            if (running) R.string.button_stop_automation else R.string.button_start_automation
        )
    }

    private fun setStatus(text: String) {
        if (text.isNotEmpty()) binding.tvStatus.text = text
    }

    companion object {
        private const val BASE_URL = "https://app.h3z.jp/games/dqa5/dqadventure5.cgi"
    }
}
