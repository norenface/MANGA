package com.babycall.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.call.CallActivity
import com.babycall.databinding.ActivityParentHomeBinding
import com.babycall.pairing.PairingRepository
import kotlinx.coroutines.launch

class ParentHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentHomeBinding
    private lateinit var prefs: Prefs
    private val repo = PairingRepository()
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
        binding.btnGeneratePairingCode.setOnClickListener { generateCode() }
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

        if (prefs.isLocalMode) {
            babyDeviceId = prefs.peerDeviceId
            val paired = babyDeviceId != null
            binding.groupNoBaby.visibility = if (paired) android.view.View.GONE else android.view.View.VISIBLE
            binding.btnGeneratePairingCode.visibility = android.view.View.GONE
            binding.btnCall.isEnabled = paired
            binding.tvBabyStatus.text = getString(
                if (paired) R.string.baby_connected else R.string.baby_not_connected_local
            )
            return
        }

        lifecycleScope.launch {
            AuthGate.ensureSignedIn()
            babyDeviceId = repo.findBabyDeviceId(familyId)
            val paired = babyDeviceId != null
            binding.groupNoBaby.visibility = if (paired) android.view.View.GONE else android.view.View.VISIBLE
            binding.btnCall.isEnabled = paired
            binding.tvBabyStatus.text = if (paired) {
                getString(R.string.baby_connected)
            } else {
                getString(R.string.baby_not_connected)
            }
        }
    }

    private fun generateCode() {
        val familyId = prefs.familyId ?: return
        lifecycleScope.launch {
            AuthGate.ensureSignedIn()
            val code = repo.generatePairingCode(familyId)
            binding.tvGeneratedCode.text = code.chunked(3).joinToString(" ")
            binding.tvGeneratedCode.visibility = android.view.View.VISIBLE
        }
    }

    private fun onInviteFamilyClicked() {
        val familyId = prefs.familyId ?: return
        binding.btnInviteFamily.isEnabled = false
        lifecycleScope.launch {
            try {
                AuthGate.ensureSignedIn()
                val code = repo.generatePairingCode(familyId, forRole = "parent")
                AlertDialog.Builder(this@ParentHomeActivity)
                    .setTitle(R.string.invite_dialog_title)
                    .setMessage(getString(R.string.invite_dialog_message, code.chunked(3).joinToString(" ")))
                    .setPositiveButton(R.string.button_confirm, null)
                    .show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@ParentHomeActivity, e.message ?: getString(R.string.error_generic), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                binding.btnInviteFamily.isEnabled = true
            }
        }
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
