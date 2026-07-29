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
import com.babycall.databinding.ActivityParentSetupBinding
import com.babycall.home.ParentHomeActivity
import com.babycall.local.LocalPairingHost
import com.babycall.peer.PeerPairingHost
import com.babycall.peer.PeerProtocol

class ParentSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentSetupBinding
    private lateinit var prefs: Prefs
    private var localHost: LocalPairingHost? = null
    private var onlineHost: PeerPairingHost? = null
    private var paired = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled lazily; call screen re-checks before starting a call */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        requestMediaPermissionsIfNeeded()
        showInputStep()

        binding.btnCreateFamily.setOnClickListener { onCreateFamilyClicked() }
        binding.btnDone.setOnClickListener {
            startActivity(Intent(this, ParentHomeActivity::class.java))
            finish()
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun onCreateFamilyClicked() {
        val name = binding.etParentName.text?.toString()?.trim().orEmpty().ifEmpty { "パパ・ママ" }
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
        binding.btnCreateFamily.isEnabled = false

        if (isLocal) {
            createFamilyLocal(name, pin)
        } else {
            createFamilyOnline(name, pin)
        }
    }

    private fun createFamilyLocal(name: String, pin: String) {
        prefs.role = "parent"
        prefs.transportMode = Prefs.TRANSPORT_LOCAL
        prefs.deviceName = name
        prefs.pinHash = Prefs.hashPin(pin)
        prefs.autoAnswer = true

        val host = LocalPairingHost(this)
        localHost = host
        prefs.familyId = host.familyId
        prefs.localAuthToken = host.authToken

        host.start(
            parentName = name,
            pinHash = Prefs.hashPin(pin),
            autoAnswer = true,
            parentDeviceId = prefs.deviceId,
            onPaired = { _, babyDeviceId ->
                runOnUiThread {
                    paired = true
                    prefs.peerDeviceId = babyDeviceId
                    showCodeStep(host.code, connected = true)
                }
            },
            onRejectedAttempt = {
                runOnUiThread { showError(getString(R.string.error_pairing_wrong_code_attempt)) }
            }
        )

        showCodeStep(host.code, connected = false)
    }

    private fun createFamilyOnline(name: String, pin: String) {
        prefs.role = "parent"
        prefs.transportMode = Prefs.TRANSPORT_CLOUD
        prefs.deviceName = name
        prefs.pinHash = Prefs.hashPin(pin)
        prefs.autoAnswer = true

        val code = PeerProtocol.randomFamilyCode()
        prefs.familyId = code

        val host = PeerPairingHost()
        onlineHost = host
        host.start(
            familyCode = code,
            parentName = name,
            pinHash = Prefs.hashPin(pin),
            autoAnswer = true,
            parentDeviceId = prefs.deviceId,
            onPaired = { _, babyDeviceId ->
                runOnUiThread {
                    paired = true
                    prefs.peerDeviceId = babyDeviceId
                    showCodeStep(code, connected = true)
                }
            },
            onError = { message ->
                runOnUiThread {
                    showError(message)
                    binding.btnCreateFamily.isEnabled = true
                }
            }
        )

        showCodeStep(code, connected = false)
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }

    private fun showInputStep() {
        binding.groupInput.visibility = android.view.View.VISIBLE
        binding.groupCode.visibility = android.view.View.GONE
    }

    private fun showCodeStep(code: String, connected: Boolean) {
        binding.groupInput.visibility = android.view.View.GONE
        binding.groupCode.visibility = android.view.View.VISIBLE
        binding.tvCode.text = code.chunked(3).joinToString(" ")
        binding.btnDone.isEnabled = connected
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!paired) {
            localHost?.stop()
            onlineHost?.stop()
        }
    }
}
