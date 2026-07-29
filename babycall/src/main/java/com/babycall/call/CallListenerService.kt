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
import com.babycall.Prefs
import com.babycall.R
import com.babycall.RoleSelectActivity
import com.babycall.local.LocalCallServerHolder
import com.babycall.peer.PeerHubHolder
import com.babycall.peer.PeerSessionSubscription

/**
 * Runs continuously on the baby's device so an incoming call can be picked
 * up (auto-answered) even if the app was swiped away or the device just
 * rebooted. This is the only thing on the baby device that "listens" for
 * anything, and it only ever reacts to the single paired family (online
 * mode: the peer broker via [PeerHubHolder]; local mode: the socket server
 * in [LocalCallServerHolder]).
 */
class CallListenerService : LifecycleService() {

    private var hubSubscription: PeerSessionSubscription? = null
    private var callActivityLaunched = false
    private var ringingCount = 0

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
            startOnlineListening(prefs)
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

    private fun startOnlineListening(prefs: Prefs) {
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

        // Only decides whether to launch CallActivity for the first viewer
        // of a new call; once it's open, CallActivity has its own
        // subscription that picks up every viewer (including ones who join
        // later, mid-call) on its own. Settings pushed by a parent-role
        // device are applied directly to prefs inside PeerHub itself, so
        // there is nothing to separately observe here.
        hubSubscription = hub.observeSessions(
            onNewSession = { _, _ ->
                ringingCount++
                if (!callActivityLaunched) {
                    callActivityLaunched = true
                    launchCallActivity("")
                }
            },
            onSessionEnded = { _ ->
                ringingCount = (ringingCount - 1).coerceAtLeast(0)
                if (ringingCount == 0) callActivityLaunched = false
            }
        )
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
        hubSubscription?.let { sub ->
            runCatching { PeerHubHolder.getOrCreate(Prefs(this)).removeSubscriber(sub) }
        }
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
