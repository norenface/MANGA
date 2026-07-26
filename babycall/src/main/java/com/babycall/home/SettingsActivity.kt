package com.babycall.home

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivitySettingsBinding
import com.babycall.pairing.PairingRepository
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private val repo = PairingRepository()
    private lateinit var familyId: String
    private var settingsListener: ValueEventListener? = null
    private var babyDeviceId: String? = null
    private var suppressAutoAnswerCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        familyId = prefs.familyId ?: run { finish(); return }

        binding.switchAutoAnswer.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoAnswerCallback) return@setOnCheckedChangeListener
            lifecycleScope.launch { runCatching { repo.setAutoAnswer(familyId, isChecked) } }
        }

        binding.btnSavePin.setOnClickListener { onSavePinClicked() }
        binding.btnUnpairBaby.setOnClickListener { confirmUnpairBaby() }

        lifecycleScope.launch {
            AuthGate.ensureSignedIn()

            settingsListener = repo.observeSettings(familyId) { settings ->
                suppressAutoAnswerCallback = true
                binding.switchAutoAnswer.isChecked = settings.autoAnswer
                suppressAutoAnswerCallback = false
            }

            babyDeviceId = repo.findBabyDeviceId(familyId)
            binding.btnUnpairBaby.isEnabled = babyDeviceId != null
        }
    }

    private fun onSavePinClicked() {
        val pin = binding.etNewPin.text?.toString()?.trim().orEmpty()
        val confirm = binding.etNewPinConfirm.text?.toString()?.trim().orEmpty()

        if (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() }) {
            binding.tvSettingsError.text = getString(R.string.error_pin_format)
            binding.tvSettingsError.visibility = android.view.View.VISIBLE
            return
        }
        if (pin != confirm) {
            binding.tvSettingsError.text = getString(R.string.error_pin_mismatch)
            binding.tvSettingsError.visibility = android.view.View.VISIBLE
            return
        }
        binding.tvSettingsError.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val hash = Prefs.hashPin(pin)
            runCatching { repo.setPin(familyId, hash) }
            prefs.pinHash = hash
            binding.etNewPin.text?.clear()
            binding.etNewPinConfirm.text?.clear()
            binding.tvSettingsSaved.visibility = android.view.View.VISIBLE
        }
    }

    private fun confirmUnpairBaby() {
        val deviceId = babyDeviceId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_baby_confirm_title)
            .setMessage(R.string.unpair_baby_confirm_message)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                lifecycleScope.launch {
                    runCatching { repo.unpairDevice(familyId, deviceId) }
                    babyDeviceId = null
                    binding.btnUnpairBaby.isEnabled = false
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsListener?.let { repo.removeSettingsListener(familyId, it) }
    }
}
