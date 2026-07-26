package com.babycall.signaling

import android.content.Context
import com.babycall.Prefs
import com.babycall.local.LocalCallClient
import com.babycall.local.LocalCallServerHolder
import com.babycall.webrtc.SignalingRepository

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
            SignalingRepository(familyId)
        }
    }
}
