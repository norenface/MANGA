package com.babycall.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityJoinFamilyBinding
import com.babycall.home.ParentHomeActivity
import com.babycall.local.DiscoveredParent
import com.babycall.local.LocalPairingClient
import com.babycall.peer.PeerControlClient
import com.babycall.peer.PeerProtocol
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lets anyone (the first family member or the tenth) start calling an
 * already-set-up baby device, using the number the baby's own setup screen
 * showed. There is no separate "become the parent first" step -- every
 * viewer joins the same way, online or on the same Wi-Fi.
 */
class JoinFamilyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJoinFamilyBinding
    private lateinit var prefs: Prefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        binding.tvPermissionWarning.visibility =
            if (result.values.all { it }) android.view.View.GONE else android.view.View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinFamilyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())

        binding.btnJoin.setOnClickListener { onJoinClicked() }
    }

    private fun onJoinClicked() {
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifEmpty { "家族" }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnJoin.isEnabled = false

        if (binding.radioLocal.isChecked) {
            val code = binding.etCode.text?.toString()?.replace(Regex("[^0-9]"), "").orEmpty()
            if (code.length != 6) {
                showError(getString(R.string.error_code_format))
                binding.btnJoin.isEnabled = true
                return
            }
            joinLocal(code, name)
        } else {
            val code = PeerProtocol.normalizeCode(binding.etCode.text?.toString().orEmpty())
            if (code.length < 6) {
                showError(getString(R.string.error_code_format))
                binding.btnJoin.isEnabled = true
                return
            }
            joinOnline(code, name)
        }
    }

    private fun joinOnline(code: String, name: String) {
        lifecycleScope.launch {
            val deviceId = prefs.deviceId
            val ok = PeerControlClient.joinFamily(code, deviceId, name)
            if (!ok) {
                showError(getString(R.string.error_join_family_unreachable))
                binding.btnJoin.isEnabled = true
                return@launch
            }

            prefs.role = "parent"
            prefs.transportMode = Prefs.TRANSPORT_CLOUD
            prefs.familyId = code
            prefs.deviceName = name

            goHome()
        }
    }

    private fun joinLocal(code: String, name: String) {
        lifecycleScope.launch {
            try {
                binding.tvError.text = getString(R.string.status_searching_parent)
                binding.tvError.visibility = android.view.View.VISIBLE
                val hosts = LocalPairingClient.discoverHosts(this@JoinFamilyActivity)

                val chosen: DiscoveredParent = when {
                    hosts.isEmpty() -> {
                        showError(getString(R.string.error_no_parent_found))
                        binding.btnJoin.isEnabled = true
                        return@launch
                    }
                    hosts.size == 1 -> hosts.first()
                    else -> pickHost(hosts) ?: run {
                        binding.btnJoin.isEnabled = true
                        return@launch
                    }
                }

                val paired = LocalPairingClient.redeem(chosen, code, name, prefs.deviceId)

                prefs.role = "parent"
                prefs.transportMode = Prefs.TRANSPORT_LOCAL
                prefs.familyId = paired.familyId
                prefs.localAuthToken = paired.authToken
                prefs.peerDeviceId = paired.hostDeviceId
                prefs.deviceName = name
                prefs.pinHash = paired.pinHash
                prefs.autoAnswer = paired.autoAnswer

                goHome()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
                binding.btnJoin.isEnabled = true
            }
        }
    }

    private suspend fun pickHost(hosts: List<DiscoveredParent>): DiscoveredParent? =
        suspendCancellableCoroutine { cont ->
            val names = hosts.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_parent_title)
                .setItems(names) { _, which -> cont.resume(hosts[which]) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }

    private fun goHome() {
        startActivity(Intent(this@JoinFamilyActivity, ParentHomeActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }
}
