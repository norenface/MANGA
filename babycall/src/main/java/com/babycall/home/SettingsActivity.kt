package com.babycall.home

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.databinding.ActivitySettingsBinding
import com.babycall.local.LocalControlChannel
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
            onAutoAnswerChanged(isChecked)
        }

        binding.btnSavePin.setOnClickListener { onSavePinClicked() }
        binding.btnUnpairBaby.setOnClickListener { confirmUnpairBaby() }

        if (prefs.isLocalMode) {
            suppressAutoAnswerCallback = true
            binding.switchAutoAnswer.isChecked = prefs.autoAnswer
            suppressAutoAnswerCallback = false
            babyDeviceId = prefs.peerDeviceId
            binding.btnUnpairBaby.isEnabled = babyDeviceId != null
        } else {
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
    }

    private fun onAutoAnswerChanged(isChecked: Boolean) {
        if (prefs.isLocalMode) {
            prefs.autoAnswer = isChecked
            lifecycleScope.launch {
                val ok = LocalControlChannel.pushSettings(this@SettingsActivity, prefs, pinHash = null, autoAnswer = isChecked)
                if (!ok) showTransientError(getString(R.string.error_local_baby_unreachable))
            }
        } else {
            lifecycleScope.launch { runCatching { repo.setAutoAnswer(familyId, isChecked) } }
        }
    }

    private fun onSavePinClicked() {
        val pin = binding.etNewPin.text?.toString()?.trim().orEmpty()
        val confirm = binding.etNewPinConfirm.text?.toString()?.trim().orEmpty()

        if (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() }) {
            showTransientError(getString(R.string.error_pin_format))
            return
        }
        if (pin != confirm) {
            showTransientError(getString(R.string.error_pin_mismatch))
            return
        }
        binding.tvSettingsError.visibility = android.view.View.GONE

        lifecycleScope.launch {
            val hash = Prefs.hashPin(pin)
            if (prefs.isLocalMode) {
                val ok = LocalControlChannel.pushSettings(this@SettingsActivity, prefs, pinHash = hash, autoAnswer = prefs.autoAnswer)
                if (!ok) {
                    showTransientError(getString(R.string.error_local_baby_unreachable))
                    return@launch
                }
            } else {
                runCatching { repo.setPin(familyId, hash) }
            }
            prefs.pinHash = hash
            binding.etNewPin.text?.clear()
            binding.etNewPinConfirm.text?.clear()
            binding.tvSettingsSaved.visibility = android.view.View.VISIBLE
        }
    }

    private fun confirmUnpairBaby() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_baby_confirm_title)
            .setMessage(R.string.unpair_baby_confirm_message)
            .setPositiveButton(R.string.button_confirm) { _, _ -> doUnpairBaby() }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun doUnpairBaby() {
        if (prefs.isLocalMode) {
            lifecycleScope.launch {
                val notified = LocalControlChannel.pushUnpair(this@SettingsActivity, prefs)
                if (!notified) {
                    showTransientError(getString(R.string.error_local_unpair_not_delivered))
                }
                prefs.peerDeviceId = null
                babyDeviceId = null
                binding.btnUnpairBaby.isEnabled = false
            }
        } else {
            val deviceId = babyDeviceId ?: return
            lifecycleScope.launch {
                runCatching { repo.unpairDevice(familyId, deviceId) }
                babyDeviceId = null
                binding.btnUnpairBaby.isEnabled = false
            }
        }
    }

    private fun showTransientError(message: String) {
        binding.tvSettingsError.text = message
        binding.tvSettingsError.visibility = android.view.View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsListener?.let { repo.removeSettingsListener(familyId, it) }
    }
}
