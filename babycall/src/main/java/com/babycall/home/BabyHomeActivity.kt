package com.babycall.home

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
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
        showExitHintThenLock()

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

        // On gesture-navigation devices, a touch this close to the screen
        // edge can otherwise be swallowed by the system's own edge-swipe
        // gesture detection before it ever reaches this view. Carving out
        // its exact bounds tells the system to leave touches here alone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.hiddenExitDot.doOnLayout { view ->
                view.systemGestureExclusionRects = listOf(Rect(0, 0, view.width, view.height))
            }
        }
    }

    /**
     * The exit gesture is invisible on purpose (see [R.layout.activity_baby_home]'s
     * hiddenExitDot) so a baby can't find it by chance -- but that means the
     * caregiver doing setup needs to be told where it is and how it works,
     * every time this screen starts, since it's the only way back out.
     */
    private fun showExitHintThenLock() {
        AlertDialog.Builder(this)
            .setTitle(R.string.baby_home_exit_hint_title)
            .setMessage(R.string.baby_home_exit_hint_message)
            .setCancelable(false)
            .setPositiveButton(R.string.button_confirm) { _, _ ->
                try {
                    startLockTask()
                } catch (_: Exception) {
                }
            }
            .show()
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
