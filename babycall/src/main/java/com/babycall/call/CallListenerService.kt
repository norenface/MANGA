package com.babycall.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.babycall.AuthGate
import com.babycall.Prefs
import com.babycall.R
import com.babycall.RoleSelectActivity
import com.babycall.model.CallState
import com.babycall.local.LocalCallServerHolder
import com.babycall.pairing.PairingRepository
import com.babycall.webrtc.SignalingRepository
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

/**
 * Runs continuously on the baby's device so an incoming call can be picked
 * up (auto-answered) even if the app was swiped away or the device just
 * rebooted. This is the only thing on the baby device that "listens" for
 * anything, and it only ever reacts to the single paired family (cloud
 * mode: the Firebase call node; local mode: the socket server in
 * [LocalCallServerHolder]).
 */
class CallListenerService : LifecycleService() {

    private var cloudSignaling: SignalingRepository? = null
    private val pairingRepo = PairingRepository()
    private var settingsListener: ValueEventListener? = null
    private var familyIdForSettings: String? = null
    private var callActivityLaunched = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val prefs = Prefs(this)
        val familyId = prefs.familyId
        if (prefs.role != "baby" || familyId == null) {
            stopSelf()
            return
        }

        if (prefs.isLocalMode) {
            startLocalListening(prefs, familyId)
        } else {
            startCloudListening(prefs, familyId)
        }
    }

    private fun startLocalListening(prefs: Prefs, familyId: String) {
        val server = LocalCallServerHolder.getOrCreate(this, prefs)
        server.onIncomingCall = { callerId ->
            if (!callActivityLaunched) {
                callActivityLaunched = true
                launchCallActivity(callerId)
            }
        }
        server.onCallEnded = {
            callActivityLaunched = false
        }
        server.onUnpaired = {
            prefs.clearPairing()
            LocalCallServerHolder.stop()
            val intent = Intent(this, RoleSelectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            stopSelf()
        }
        server.start()
    }

    private fun startCloudListening(prefs: Prefs, familyId: String) {
        val myDeviceId = prefs.deviceId
        val repo = SignalingRepository(familyId)
        cloudSignaling = repo
        familyIdForSettings = familyId

        lifecycleScope.launch {
            runCatching { AuthGate.ensureSignedIn() }

            repo.observeCallInfo { info ->
                when (info.state) {
                    CallState.RINGING -> {
                        if (info.calleeId == myDeviceId && !callActivityLaunched) {
                            callActivityLaunched = true
                            launchCallActivity(info.callerId)
                        }
                    }
                    CallState.ENDED, CallState.IDLE -> {
                        callActivityLaunched = false
                    }
                    else -> {}
                }
            }

            // Keep the baby device's local copy of the PIN/auto-answer current
            // so CallActivity/BabyHomeActivity never need their own Firebase
            // round-trip to check them.
            settingsListener = pairingRepo.observeSettings(familyId) { settings ->
                prefs.pinHash = settings.pinHash
                prefs.autoAnswer = settings.autoAnswer
            }
        }
    }

    private fun launchCallActivity(callerId: String) {
        val intent = Intent(this, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CallActivity.EXTRA_CALLER_ID, callerId)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_baby_listener),
                NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_listener_title))
            .setContentText(getString(R.string.notification_listener_text))
            .setSmallIcon(R.drawable.ic_notification_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cloudSignaling?.release()
        familyIdForSettings?.let { fid -> settingsListener?.let { pairingRepo.removeSettingsListener(fid, it) } }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val CHANNEL_ID = "babycall_listener"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CallListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
