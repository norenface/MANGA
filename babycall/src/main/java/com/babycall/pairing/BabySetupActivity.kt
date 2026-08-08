package com.babycall.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.babycall.Prefs
import com.babycall.R
import com.babycall.call.CallListenerService
import com.babycall.databinding.ActivityBabySetupBinding
import com.babycall.home.BabyHomeActivity
import com.babycall.launcher.AppPickerActivity
import com.babycall.local.LocalPairingHost
import com.babycall.peer.PeerProtocol
import com.babycall.screenshare.ScreenShareSetupActivity
import com.babycall.util.ClipboardUtil

/**
 * Sets up this device as the baby's: generates the family's code itself
 * (no separate "parent setup" step needed) and starts listening for
 * whoever redeems it. Local mode hosts a live LAN pairing handshake (see
 * [LocalPairingHost]); online mode has nothing to wait for since there's
 * no server-side record to create -- the code is ready the moment it's
 * generated.
 */
class BabySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBabySetupBinding
    private lateinit var prefs: Prefs
    private var localHost: LocalPairingHost? = null
    private var paired = false
    private var currentCode: String? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            binding.tvPermissionWarning.visibility = android.view.View.GONE
        } else {
            binding.tvPermissionWarning.visibility = android.view.View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBabySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())

        binding.btnCreateBaby.setOnClickListener { onCreateBabyClicked() }
        binding.btnManageApps.setOnClickListener {
            startActivity(Intent(this@BabySetupActivity, AppPickerActivity::class.java))
        }
        binding.btnScreenShareSetup.setOnClickListener {
            startActivity(Intent(this@BabySetupActivity, ScreenShareSetupActivity::class.java))
        }
        binding.btnCopyCode.setOnClickListener {
            currentCode?.let { code -> ClipboardUtil.copyFamilyCode(this@BabySetupActivity, code) }
        }
        binding.btnDone.setOnClickListener {
            CallListenerService.start(this@BabySetupActivity)
            startActivity(Intent(this@BabySetupActivity, BabyHomeActivity::class.java))
            finish()
        }
    }

    private fun onCreateBabyClicked() {
        val name = binding.etBabyName.text?.toString()?.trim().orEmpty().ifEmpty { "赤ちゃん" }
        val pin = binding.etPin.text?.toString()?.trim().orEmpty()
        val pinConfirm = binding.etPinConfirm.text?.toString()?.trim().orEmpty()
        val isLocal = binding.radioLocal.isChecked

        if (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() }) {
            showError(getString(R.string.error_pin_format))
            return
        }
        if (pin != pinConfirm) {
            showError(getString(R.string.error_pin_mismatch))
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnCreateBaby.isEnabled = false

        if (isLocal) {
            createBabyLocal(name, pin)
        } else {
            createBabyOnline(name, pin)
        }
    }

    private fun createBabyLocal(name: String, pin: String) {
        prefs.role = "baby"
        prefs.transportMode = Prefs.TRANSPORT_LOCAL
        prefs.deviceName = name
        prefs.pinHash = Prefs.hashPin(pin)
        prefs.autoAnswer = true

        val host = LocalPairingHost(this)
        localHost = host
        prefs.familyId = host.familyId
        prefs.localAuthToken = host.authToken

        host.start(
            hostName = name,
            pinHash = Prefs.hashPin(pin),
            autoAnswer = true,
            hostDeviceId = prefs.deviceId,
            onPaired = { _, redeemerDeviceId ->
                runOnUiThread {
                    paired = true
                    prefs.peerDeviceId = redeemerDeviceId
                    showCodeStep(host.code, connected = true)
                }
            },
            onRejectedAttempt = {
                runOnUiThread { showError(getString(R.string.error_pairing_wrong_code_attempt)) }
            }
        )

        showCodeStep(host.code, connected = false)
    }

    private fun createBabyOnline(name: String, pin: String) {
        prefs.role = "baby"
        prefs.transportMode = Prefs.TRANSPORT_CLOUD
        prefs.deviceName = name
        prefs.pinHash = Prefs.hashPin(pin)
        prefs.autoAnswer = true

        val code = PeerProtocol.randomFamilyCode()
        prefs.familyId = code
        paired = true

        showCodeStep(code, connected = true)
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }

    private fun showCodeStep(code: String, connected: Boolean) {
        currentCode = code
        binding.groupInput.visibility = android.view.View.GONE
        binding.groupCode.visibility = android.view.View.VISIBLE
        binding.tvCode.text = code.chunked(3).joinToString(" ")
        binding.tvCodeInstructions.text = getString(
            if (connected) R.string.baby_code_instructions_ready else R.string.baby_code_instructions_waiting
        )
        binding.btnDone.isEnabled = connected
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!paired) localHost?.stop()
    }
}
