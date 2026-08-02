package com.babycall.home

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
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
import com.babycall.databinding.ItemBabyAppIconBinding
import com.babycall.launcher.AppPickerActivity
import com.babycall.local.LocalCallServerHolder
import com.babycall.peer.PeerHubHolder
import com.babycall.screenshare.ScreenShareSetupActivity
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

    /** True until the one-time exit-hint dialog's own confirm button has
     *  engaged lock task for the first time; guards [onResume] from
     *  re-locking (and thus fighting the dialog) before the caregiver has
     *  even read it. */
    private var exitHintPending = true

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

    override fun onResume() {
        super.onResume()
        if (!prefs.isPaired || prefs.role != "baby") return
        refreshAppGrid()
        // Skipped on the very first resume after onCreate: the exit-hint
        // dialog hasn't been confirmed yet, and it engages lock task itself.
        // On every later resume (e.g. returning from a registered app that
        // dropped lock task to launch), re-engage it here.
        if (!exitHintPending) {
            try {
                startLockTask()
            } catch (_: Exception) {
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
                exitHintPending = false
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
            if (ok) showCaregiverMenu()
            ok
        }
    }

    private fun showCaregiverMenu() {
        val options = arrayOf(
            getString(R.string.caregiver_menu_manage_apps),
            getString(R.string.caregiver_menu_screen_share),
            getString(R.string.caregiver_menu_unpair)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.caregiver_menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openAppPicker()
                    1 -> openScreenShareSetup()
                    2 -> confirmUnpair()
                }
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    private fun openAppPicker() {
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun openScreenShareSetup() {
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        startActivity(Intent(this, ScreenShareSetupActivity::class.java))
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_baby_confirm_title)
            .setMessage(R.string.unpair_baby_confirm_message)
            .setPositiveButton(R.string.button_confirm) { _, _ -> doUnpair() }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    /**
     * Rebuilds the tappable app-icon grid from [Prefs.launcherAppPackages],
     * silently dropping any that were uninstalled since being registered.
     */
    private fun refreshAppGrid() {
        binding.appGrid.removeAllViews()
        prefs.launcherAppPackages.forEach { pkg ->
            val appInfo = try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                return@forEach
            }
            val itemBinding = ItemBabyAppIconBinding.inflate(LayoutInflater.from(this), binding.appGrid, false)
            itemBinding.ivAppIcon.setImageDrawable(appInfo.loadIcon(packageManager))
            itemBinding.tvAppLabel.text = appInfo.loadLabel(packageManager)
            itemBinding.root.setOnClickListener { launchRegisteredApp(pkg) }
            binding.appGrid.addView(itemBinding.root)
        }
    }

    private fun launchRegisteredApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        try {
            stopLockTask()
        } catch (_: Exception) {
        }
        startActivity(intent)
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
