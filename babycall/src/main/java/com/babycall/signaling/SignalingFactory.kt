package com.babycall.signaling

import android.content.Context
import com.babycall.Prefs
import com.babycall.local.LocalCallClient
import com.babycall.local.LocalCallServerHolder
import com.babycall.webrtc.SignalingRepository

/**
 * Builds a single-session [CallSignaling] for whichever device is calling
 * this: a viewer (in either mode), or the baby device in *local* mode only.
 * The baby device in *cloud* mode does not use this factory at all — it
 * juggles many sessions at once via [com.babycall.webrtc.SignalingRoomRepository]
 * instead (see CallActivity).
 */
object SignalingFactory {
    fun create(context: Context, prefs: Prefs): CallSignaling {
        return if (prefs.isLocalMode) {
            if (prefs.role == "baby") {
                LocalCallServerHolder.getOrCreate(context, prefs)
            } else {
                LocalCallClient(context, prefs)
            }
        } else {
            val familyId = prefs.familyId ?: error("not paired")
            SignalingRepository(familyId, sessionId = prefs.deviceId)
        }
    }
}
