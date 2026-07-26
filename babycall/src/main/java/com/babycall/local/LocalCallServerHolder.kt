package com.babycall.local

import android.content.Context
import com.babycall.Prefs

/**
 * One [LocalCallServer] per process, shared between CallListenerService
 * (which starts it and just wants to know when a call comes in) and
 * CallActivity (which uses the same instance as its [com.babycall.signaling.CallSignaling]
 * for the duration of a call). Recreating the server per-caller would mean
 * rebinding the socket/mDNS registration and losing the already-accepted
 * connection.
 */
object LocalCallServerHolder {
    @Volatile private var instance: LocalCallServer? = null

    fun getOrCreate(context: Context, prefs: Prefs): LocalCallServer {
        return instance ?: synchronized(this) {
            instance ?: LocalCallServer(context.applicationContext, prefs).also { instance = it }
        }
    }

    fun stop() {
        instance?.stop()
        instance = null
    }
}
