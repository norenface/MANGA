package com.babycall.peer

import com.babycall.Prefs

/**
 * One [PeerHub] per process, shared between CallListenerService (starts it,
 * decides whether to launch CallActivity) and CallActivity (uses the same
 * instance to observe/wire sessions for the duration of a call). Mirrors
 * [com.babycall.local.LocalCallServerHolder].
 */
object PeerHubHolder {
    @Volatile private var instance: PeerHub? = null

    fun getOrCreate(prefs: Prefs): PeerHub {
        return instance ?: synchronized(this) {
            instance ?: PeerHub(prefs).also { instance = it }
        }
    }

    fun stop() {
        instance?.stop()
        instance = null
    }
}
