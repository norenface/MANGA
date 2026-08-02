package com.babycall.screenshare

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.babycall.Prefs
import com.babycall.R
import com.babycall.call.CallListenerService
import com.babycall.databinding.ActivityScreenShareSetupBinding

/**
 * One-time (per boot) caregiver setup for sharing the baby's screen and
 * showing the video-call bubble -- both need a manual, human-in-the-loop OS
 * permission that can't be automated or granted on the baby's behalf:
 * "display over other apps" (for the bubble) and screen-recording consent
 * (for the screen share). Reachable from baby setup and from the hidden
 * PIN-gated menu on the baby device.
 */
class ScreenShareSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenShareSetupBinding
    private lateinit var prefs: Prefs

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            prefs.screenShareRequested = true
            CallListenerService.grantScreenCapture(this, data)
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenShareSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnStep1.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        binding.btnStep2.setOnClickListener {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        }
        binding.btnDone.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayGranted = canDrawOverlays()
        binding.tvStep1Status.text = getString(
            if (overlayGranted) R.string.screen_share_status_granted else R.string.screen_share_status_not_granted
        )
        binding.btnStep2.isEnabled = overlayGranted

        val screenShareGranted = prefs.screenShareRequested
        binding.tvStep2Status.text = getString(
            if (screenShareGranted) R.string.screen_share_status_granted else R.string.screen_share_status_not_granted
        )
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }
}
