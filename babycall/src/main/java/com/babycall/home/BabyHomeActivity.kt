package com.babycall.home

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.babycall.Prefs
import com.babycall.R
import com.babycall.RoleSelectActivity
import com.babycall.call.CallListenerService
import com.babycall.databinding.ActivityBabyHomeBinding
import com.babycall.local.LocalCallServerHolder
import com.babycall.peer.PeerHubHolder
import com.babycall.security.PinDialog

/**
 * Idle "waiting" screen shown on the baby device between calls. It is
 * pinned (Lock Task) so the device can't be used for anything other than
 * receiving the paired parent's call, and has no navigation of any kind
 * except a hidden, PIN-gated exit meant for the caregiver only.
 */
class BabyHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBabyHomeBinding
    private lateinit var prefs: Prefs

    private var holdRunnable: Runnable? = null
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityBabyHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (!prefs.isPaired || prefs.role != "baby") {
            startActivity(Intent(this, RoleSelectActivity::class.java))
            finish()
            return
        }

        CallListenerService.start(this)
        try {
            startLockTask()
        } catch (_: Exception) {
        }

        binding.hiddenExitDot.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val runnable = Runnable { showExitPin() }
                    holdRunnable = runnable
                    holdHandler.postDelayed(runnable, HOLD_DURATION_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { holdHandler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun showExitPin() {
        val pinHash = prefs.pinHash ?: return
        PinDialog.show(this, titleRes = R.string.pin_dialog_unpair_title) { rawPin ->
            val ok = Prefs.hashPin(rawPin) == pinHash
            if (ok) doUnpair()
            ok
        }
    }

    private fun doUnpair() {
        val wasLocalMode = prefs.isLocalMode
        prefs.clearPairing()
        if (wasLocalMode) {
            LocalCallServerHolder.stop()
        } else {
            PeerHubHolder.stop()
        }
        stopService(Intent(this, CallListenerService::class.java))
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        startActivity(Intent(this, RoleSelectActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        // No exit via back button; use the hidden hold + PIN gesture instead.
    }

    override fun onDestroy() {
        super.onDestroy()
        holdRunnable?.let { holdHandler.removeCallbacks(it) }
    }

    companion object {
        private const val HOLD_DURATION_MS = 3000L
    }
}
