package com.babycall.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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

        refreshBabyStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshBabyStatus()
    }

    private fun refreshBabyStatus() {
        val familyId = prefs.familyId ?: return
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
