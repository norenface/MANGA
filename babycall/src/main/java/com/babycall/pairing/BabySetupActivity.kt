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
import com.babycall.call.CallListenerService
import com.babycall.databinding.ActivityBabySetupBinding
import com.babycall.home.BabyHomeActivity
import kotlinx.coroutines.launch

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
            binding.tvError.text = getString(com.babycall.R.string.error_code_format)
            binding.tvError.visibility = android.view.View.VISIBLE
            return
        }
        binding.tvError.visibility = android.view.View.GONE
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                AuthGate.ensureSignedIn()
                val deviceId = prefs.deviceId
                val familyId = repo.redeemPairingCode(code, deviceId, name)

                prefs.role = "baby"
                prefs.familyId = familyId
                prefs.deviceName = name

                CallListenerService.start(this@BabySetupActivity)

                startActivity(Intent(this@BabySetupActivity, BabyHomeActivity::class.java))
                finish()
            } catch (e: Exception) {
                binding.tvError.text = e.message ?: getString(com.babycall.R.string.error_generic)
                binding.tvError.visibility = android.view.View.VISIBLE
                binding.btnConnect.isEnabled = true
            }
        }
    }
}
