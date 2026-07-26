package com.babycall.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.babycall.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.role == "baby" && prefs.isPaired) {
            CallListenerService.start(context)
        }
    }
}
