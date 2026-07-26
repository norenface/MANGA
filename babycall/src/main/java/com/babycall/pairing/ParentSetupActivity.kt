package com.babycall.pairing

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
import com.babycall.databinding.ActivityParentSetupBinding
import com.babycall.home.ParentHomeActivity
import kotlinx.coroutines.launch

class ParentSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentSetupBinding
    private lateinit var prefs: Prefs
    private val repo = PairingRepository()
    private var familyId: String? = null

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

        if (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() }) {
            binding.tvError.text = getString(R.string.error_pin_format)
            binding.tvError.visibility = android.view.View.VISIBLE
            return
        }
        if (pin != pinConfirm) {
            binding.tvError.text = getString(R.string.error_pin_mismatch)
            binding.tvError.visibility = android.view.View.VISIBLE
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnCreateFamily.isEnabled = false

        lifecycleScope.launch {
            try {
                AuthGate.ensureSignedIn()
                val deviceId = prefs.deviceId
                val newFamilyId = repo.createFamily(deviceId, name)
                repo.setPin(newFamilyId, Prefs.hashPin(pin))

                prefs.role = "parent"
                prefs.familyId = newFamilyId
                prefs.deviceName = name
                prefs.pinHash = Prefs.hashPin(pin)
                familyId = newFamilyId

                val code = repo.generatePairingCode(newFamilyId)
                showCodeStep(code)
            } catch (e: Exception) {
                binding.tvError.text = e.message ?: getString(R.string.error_generic)
                binding.tvError.visibility = android.view.View.VISIBLE
                binding.btnCreateFamily.isEnabled = true
            }
        }
    }

    private fun showInputStep() {
        binding.groupInput.visibility = android.view.View.VISIBLE
        binding.groupCode.visibility = android.view.View.GONE
    }

    private fun showCodeStep(code: String) {
        binding.groupInput.visibility = android.view.View.GONE
        binding.groupCode.visibility = android.view.View.VISIBLE
        binding.tvCode.text = code.chunked(3).joinToString(" ")
    }
}
