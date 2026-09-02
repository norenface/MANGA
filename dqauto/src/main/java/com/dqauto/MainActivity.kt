package com.dqauto

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dqauto.databinding.ActivityMainBinding
import com.dqauto.databinding.DialogLoginBinding

/**
 * There is only one WebView/login session in this app, owned by
 * [AutomationService] for its entire lifetime so the automation loop keeps
 * going after this screen is closed or the app is backgrounded. While this
 * screen is visible it borrows that same WebView into [ActivityMainBinding.webViewContainer]
 * (so the caregiver sees exactly what the automation sees, and can act on it
 * manually between cycles), and hands it back headless in [onStop].
 * "自動化を開始/停止" starts/stops the service's loop; while bound to it, this
 * screen mirrors its live status text and running state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var automationService: AutomationService? = null

    /** Set when "start" was tapped, or new credentials were saved, before the
     *  service finished (re)binding; consumed by [onServiceConnected] as soon
     *  as it does. */
    private var pendingStart = false
    private var pendingLogin: Pair<String, String>? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as AutomationService.LocalBinder).getService()
            automationService = bound
            bound.onStatusChanged = { text -> runOnUiThread { setStatus(text) } }

            val webView = bound.getWebView()
            (webView.parent as? ViewGroup)?.removeView(webView)
            binding.webViewContainer.removeAllViews()
            binding.webViewContainer.addView(
                webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            pendingLogin?.let { (id, password) ->
                pendingLogin = null
                bound.login(id, password)
            }
            if (pendingStart) {
                pendingStart = false
                bound.startAutomation()
            }
            setStatus(bound.currentStatus())
            updateToggleButton(bound.isAutomationRunning())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            automationService?.onStatusChanged = null
            automationService = null
            binding.webViewContainer.removeAllViews()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        binding.btnLoginSettings.setOnClickListener { showLoginDialog() }
        binding.btnToggleAutomation.setOnClickListener { toggleAutomation() }

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
        automationService?.let { service ->
            binding.webViewContainer.removeView(service.getWebView())
            service.prepareHeadless()
        }
        unbindService(serviceConnection)
        automationService = null
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
                    val service = automationService
                    if (service != null) {
                        service.login(id, password)
                    } else {
                        pendingLogin = id to password
                    }
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
}
