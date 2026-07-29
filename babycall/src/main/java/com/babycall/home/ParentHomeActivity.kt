package com.babycall.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.babycall.Prefs
import com.babycall.R
import com.babycall.call.CallActivity
import com.babycall.databinding.ActivityParentHomeBinding

class ParentHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentHomeBinding
    private lateinit var prefs: Prefs
    private var babyDeviceId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCall()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnCall.setOnClickListener { onCallClicked() }
        binding.btnInviteFamily.setOnClickListener { onInviteFamilyClicked() }
        binding.btnInviteFamily.visibility =
            if (prefs.isLocalMode) android.view.View.GONE else android.view.View.VISIBLE

        refreshBabyStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshBabyStatus()
    }

    private fun refreshBabyStatus() {
        val familyId = prefs.familyId ?: return

        // Both modes cache the paired baby's identity locally from pairing
        // time -- neither has a server-side directory to look this up live,
        // so "paired" here means "successfully paired at some point", not
        // "currently reachable this instant" (that's only known once you
        // actually try to call).
        babyDeviceId = prefs.peerDeviceId
        val paired = babyDeviceId != null
        binding.groupNoBaby.visibility = if (paired) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnGeneratePairingCode.visibility = android.view.View.GONE
        binding.btnCall.isEnabled = paired
        binding.tvBabyStatus.text = getString(
            when {
                paired -> R.string.baby_connected
                prefs.isLocalMode -> R.string.baby_not_connected_local
                else -> R.string.baby_not_connected
            }
        )

        if (!paired && !prefs.isLocalMode) {
            // The family code never expires, so the same one shown at setup
            // time still works for a baby device that hasn't redeemed it yet.
            binding.tvGeneratedCode.text = familyId.chunked(3).joinToString(" ")
            binding.tvGeneratedCode.visibility = android.view.View.VISIBLE
        }

        if (prefs.isLocalMode) {
            if (paired) {
                binding.tvOnlineStatus.visibility = android.view.View.VISIBLE
                binding.tvOnlineStatus.text = getString(
                    if (prefs.peerPublicHost != null) R.string.online_status_ready else R.string.online_status_unknown
                )
            } else {
                binding.tvOnlineStatus.visibility = android.view.View.GONE
            }
        }
    }

    private fun onInviteFamilyClicked() {
        val familyId = prefs.familyId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.invite_dialog_title)
            .setMessage(getString(R.string.invite_dialog_message, familyId.chunked(3).joinToString(" ")))
            .setPositiveButton(R.string.button_confirm, null)
            .show()
    }

    private fun onCallClicked() {
        if (babyDeviceId == null) return
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            startCall()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCall() {
        val calleeId = babyDeviceId ?: return
        startActivity(
            Intent(this, CallActivity::class.java).putExtra(CallActivity.EXTRA_CALLEE_ID, calleeId)
        )
    }
}
