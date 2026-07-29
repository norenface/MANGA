package com.babycall.signaling

import android.content.Context
import com.babycall.Prefs
import com.babycall.local.LocalCallClient
import com.babycall.local.LocalCallServerHolder

/**
 * Builds a single-session [CallSignaling] for local (same-Wi-Fi) mode: the
 * baby device (a persistent, single-viewer server) or a viewer (a client
 * that discovers it via mDNS). Online mode does not use this factory — see
 * [com.babycall.peer.PeerHubHolder] (baby, many simultaneous viewers) and
 * [com.babycall.peer.PeerViewerConnection] (viewer) instead, both wired
 * directly in CallActivity.
 */
object SignalingFactory {
    fun create(context: Context, prefs: Prefs): CallSignaling {
        return if (prefs.role == "baby") {
            LocalCallServerHolder.getOrCreate(context, prefs)
        } else {
            LocalCallClient(context, prefs)
        }
    }
}
