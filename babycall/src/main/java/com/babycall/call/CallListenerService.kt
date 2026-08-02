package com.babycall.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.babycall.Prefs
import com.babycall.R
import com.babycall.RoleSelectActivity
import com.babycall.local.LocalCallServerHolder
import com.babycall.peer.PeerHubHolder

/**
 * Runs continuously on the baby's device so a call can be picked up (and,
 * if granted, the screen shared) even if the app was swiped away or the
 * device just rebooted. This is the only thing on the baby device that
 * "listens" for anything, and it only ever reacts to the single paired
 * family (online mode: the peer broker via [PeerHubHolder]; local mode: the
 * socket server in [LocalCallServerHolder]). All of the actual call
 * handling -- WebRTC connections, the floating video bubble, screen share
 * -- lives in [BabyCallManager], owned here for this service's whole
 * lifetime so it survives regardless of which Activity (if any) is in the
 * foreground.
 */
class CallListenerService : LifecycleService() {

    private var callManager: BabyCallManager? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType(includeMediaProjection = false)

        val prefs = Prefs(this)
        val familyId = prefs.familyId
        if (prefs.role != "baby" || familyId == null) {
            stopSelf()
            return
        }

        val manager = BabyCallManager(this, prefs)
        callManager = manager

        if (prefs.isLocalMode) {
            startLocalListening(prefs, manager)
        } else {
            startOnlineListening(prefs, manager)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent != null && intent.action == ACTION_GRANT_SCREEN_CAPTURE) {
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            }
            if (resultData != null) {
                Prefs(this).screenShareRequested = true
                // Android 14+ requires the mediaProjection foreground service type to
                // only be declared once real user consent has been obtained (exactly
                // what resultData being non-null here means) -- declaring it any
                // earlier, e.g. unconditionally in onCreate(), throws immediately.
                startForegroundWithType(includeMediaProjection = true)
                callManager?.grantScreenCapture(resultData)
            }
        }
        return START_STICKY
    }

    private fun startLocalListening(prefs: Prefs, manager: BabyCallManager) {
        val server = LocalCallServerHolder.getOrCreate(this, prefs)
        server.onIncomingCall = { callerId -> manager.handleLocalIncomingCall(callerId) }
        server.onCallEnded = { manager.handleLocalCallEnded() }
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

    private fun startOnlineListening(prefs: Prefs, manager: BabyCallManager) {
        val hub = PeerHubHolder.getOrCreate(prefs)
        hub.onUnpaired = {
            prefs.clearPairing()
            PeerHubHolder.stop()
            val intent = Intent(this, RoleSelectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            stopSelf()
        }
        hub.start()
        manager.startOnlineListening()
    }

    /** [includeMediaProjection] must only ever be true once real MediaProjection
     *  consent has actually been obtained (see the ACTION_GRANT_SCREEN_CAPTURE
     *  handling below) -- declaring that foreground service type before consent
     *  exists throws a SecurityException on Android 14+. */
    private fun startForegroundWithType(includeMediaProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        callManager?.release()
        callManager = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        private const val CHANNEL_ID = "babycall_listener"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_GRANT_SCREEN_CAPTURE = "com.babycall.action.GRANT_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context) {
            val intent = Intent(context, CallListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Hands a freshly-granted MediaProjection consent token to the running listener service. */
        fun grantScreenCapture(context: Context, resultData: Intent) {
            val intent = Intent(context, CallListenerService::class.java).apply {
                action = ACTION_GRANT_SCREEN_CAPTURE
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
