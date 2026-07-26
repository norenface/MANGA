package com.babycall.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.call.CallListenerService
import com.babycall.databinding.ActivityBabySetupBinding
import com.babycall.home.BabyHomeActivity
import com.babycall.local.DiscoveredParent
import com.babycall.local.LocalPairingClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BabySetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBabySetupBinding
    private lateinit var prefs: Prefs
    private val repo = PairingRepository()

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

        binding.btnConnect.setOnClickListener { onConnectClicked() }
    }

    private fun onConnectClicked() {
        val code = binding.etCode.text?.toString()?.replace(Regex("[^0-9]"), "").orEmpty()
        val name = binding.etBabyName.text?.toString()?.trim().orEmpty().ifEmpty { "赤ちゃん" }

        if (code.length != 6) {
            showError(getString(R.string.error_code_format))
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnConnect.isEnabled = false

        if (binding.radioLocal.isChecked) {
            connectLocal(code, name)
        } else {
            connectCloud(code, name)
        }
    }

    private fun connectCloud(code: String, name: String) {
        lifecycleScope.launch {
            try {
                AuthGate.ensureSignedIn()
                val deviceId = prefs.deviceId
                val familyId = repo.redeemPairingCode(code, deviceId, name)
                val settings = runCatching { repo.getSettings(familyId) }.getOrNull()

                prefs.role = "baby"
                prefs.transportMode = Prefs.TRANSPORT_CLOUD
                prefs.familyId = familyId
                prefs.deviceName = name
                if (settings != null) {
                    prefs.pinHash = settings.pinHash
                    prefs.autoAnswer = settings.autoAnswer
                }

                finishPairing()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
                binding.btnConnect.isEnabled = true
            }
        }
    }

    private fun connectLocal(code: String, name: String) {
        lifecycleScope.launch {
            try {
                binding.tvError.text = getString(R.string.status_searching_parent)
                binding.tvError.visibility = android.view.View.VISIBLE
                val hosts = LocalPairingClient.discoverHosts(this@BabySetupActivity)

                val chosen: DiscoveredParent = when {
                    hosts.isEmpty() -> {
                        showError(getString(R.string.error_no_parent_found))
                        binding.btnConnect.isEnabled = true
                        return@launch
                    }
                    hosts.size == 1 -> hosts.first()
                    else -> pickParent(hosts) ?: run {
                        binding.btnConnect.isEnabled = true
                        return@launch
                    }
                }

                val paired = LocalPairingClient.redeem(chosen, code, name, prefs.deviceId)

                prefs.role = "baby"
                prefs.transportMode = Prefs.TRANSPORT_LOCAL
                prefs.familyId = paired.familyId
                prefs.localAuthToken = paired.authToken
                prefs.peerDeviceId = paired.parentDeviceId
                prefs.deviceName = name
                prefs.pinHash = paired.pinHash
                prefs.autoAnswer = paired.autoAnswer

                finishPairing()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
                binding.btnConnect.isEnabled = true
            }
        }
    }

    private suspend fun pickParent(hosts: List<DiscoveredParent>): DiscoveredParent? =
        suspendCancellableCoroutine { cont ->
            val names = hosts.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_parent_title)
                .setItems(names) { _, which -> cont.resume(hosts[which]) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }

    private fun finishPairing() {
        CallListenerService.start(this@BabySetupActivity)
        startActivity(Intent(this@BabySetupActivity, BabyHomeActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }
}
