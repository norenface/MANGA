package com.babycall.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivityJoinFamilyBinding
import com.babycall.home.ParentHomeActivity
import com.babycall.peer.PeerControlClient
import com.babycall.peer.PeerProtocol
import kotlinx.coroutines.launch

/**
 * Lets a relative who lives elsewhere (grandparent, uncle, ...) join an
 * existing family as an additional viewer, using an invite code generated
 * from the primary parent's ParentHomeActivity. Online mode only — the
 * whole point is being reachable from a different network. Requires the
 * baby device to be online right now (see [PeerControlClient]).
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
        val code = PeerProtocol.normalizeCode(binding.etCode.text?.toString().orEmpty())
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifEmpty { "家族" }

        if (code.length < 6) {
            showError(getString(R.string.error_code_format))
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnJoin.isEnabled = false

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

            startActivity(Intent(this@JoinFamilyActivity, ParentHomeActivity::class.java))
            finish()
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }
}
