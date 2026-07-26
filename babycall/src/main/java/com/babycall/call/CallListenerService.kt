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
import com.babycall.model.CallState
import com.babycall.webrtc.SignalingRepository
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

/**
 * Runs continuously on the baby's device so an incoming call can be picked
 * up (auto-answered) even if the app was swiped away or the device just
 * rebooted. This is the only thing on the baby device that "listens" for
 * anything, and it only ever reacts to the single paired family's call node.
 */
class CallListenerService : LifecycleService() {

    private var signaling: SignalingRepository? = null
    private var listener: ValueEventListener? = null
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

        val myDeviceId = prefs.deviceId
        val repo = SignalingRepository(familyId)
        signaling = repo

        lifecycleScope.launch {
            runCatching { AuthGate.ensureSignedIn() }
            listener = repo.observeCallInfo { info ->
                when (info.state) {
                    CallState.RINGING -> {
                        if (info.calleeId == myDeviceId && !callActivityLaunched) {
                            callActivityLaunched = true
                            launchCallActivity()
                        }
                    }
                    CallState.ENDED, CallState.IDLE -> {
                        callActivityLaunched = false
                    }
                    else -> {}
                }
            }
        }
    }

    private fun launchCallActivity() {
        val intent = Intent(this, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        signaling?.let { repo -> listener?.let { repo.removeCallInfoListener(it) } }
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
