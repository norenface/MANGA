package com.babycall.call

import android.content.Context
import android.content.Intent
import com.babycall.Prefs
import com.babycall.model.CallState
import com.babycall.peer.PeerHubHolder
import com.babycall.peer.PeerSessionSubscription
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.SignalingFactory
import com.babycall.webrtc.WebRTCClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns every call the baby role is part of, for the entire lifetime of
 * [CallListenerService] -- there is no baby-facing full-screen call UI at
 * all any more. Whenever at least one viewer is connected, a small floating
 * bubble (see [CallBubbleOverlay]) shows that viewer's video near the
 * bottom-right corner, without ever taking over the screen or interrupting
 * whatever the baby is doing (including a registered third-party app in the
 * foreground). The underlying [WebRTCClient] -- camera, mic, and (if
 * granted) screen share -- is created once and kept alive for as long as
 * this manager runs, so the screen-share grant survives across separate
 * viewers coming and going (a MediaProjection consent token can only be
 * redeemed once, so it must never be re-requested mid-session).
 *
 * Only the first participant to connect is shown in the bubble; if several
 * people join at once (an online-mode family group call), the others still
 * connect normally (the baby can be seen/heard by all of them), they just
 * aren't separately displayed here -- there's no room for a grid on a small
 * ambient bubble, and showing one is enough to make the baby's presence and
 * whatever she's playing visible to whoever's watching.
 */
class BabyCallManager(private val context: Context, private val prefs: Prefs) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val bubble = CallBubbleOverlay(context)
    private var client: WebRTCClient? = null

    private val sessions = mutableMapOf<String, CallSignaling>()
    private var primaryPeerId: String? = null
    private var hubSubscription: PeerSessionSubscription? = null

    /** Online mode: subscribes to every viewer session for the paired family, for as long as this manager runs. */
    fun startOnlineListening() {
        val hub = PeerHubHolder.getOrCreate(prefs)
        hubSubscription = hub.observeSessions(
            onNewSession = { sessionId, signaling -> wireSession(sessionId, signaling) },
            onSessionEnded = { sessionId -> endSession(sessionId) }
        )
    }

    /** Local mode: called once per incoming connection by [com.babycall.local.LocalCallServerHolder]'s callback. */
    fun handleLocalIncomingCall(callerId: String) {
        wireSession(callerId, SignalingFactory.create(context, prefs))
    }

    /** Local mode: called when the (single) local session ends; local mode never has more than one at a time. */
    fun handleLocalCallEnded() {
        sessions.keys.firstOrNull()?.let { endSession(it) }
    }

    /** Called once the caregiver grants the MediaProjection consent (see ScreenShareSetupActivity). */
    fun grantScreenCapture(resultData: Intent) {
        val c = clientOrCreate()
        c.grantScreenCapturePermission(resultData)
        // Deferred until someone is actually watching -- no reason to light up
        // the system's screen-recording indicator before anyone is connected.
        if (sessions.isNotEmpty()) tryStartScreenShare(c)
    }

    private fun clientOrCreate(): WebRTCClient {
        client?.let { return it }
        val newClient = WebRTCClient(context)
        newClient.onConnectionFailed = { peerId -> endSession(peerId) }
        newClient.onScreenShareStopped = { prefs.screenShareRequested = false }
        newClient.onIceCandidate = { peerId, candidate ->
            scope.launch { runCatching { sessions[peerId]?.sendIceCandidate(prefs.deviceId, candidate) } }
        }
        client = newClient
        return newClient
    }

    private fun ensureActiveClient(): WebRTCClient {
        val c = clientOrCreate()
        if (!c.isLocalMediaActive()) c.startLocalVideo(null)
        if (prefs.screenShareRequested && c.hasScreenCapturePermission() && !c.isScreenSharing()) {
            tryStartScreenShare(c)
        }
        return c
    }

    private fun tryStartScreenShare(client: WebRTCClient) {
        val metrics = context.resources.displayMetrics
        val maxDimension = 720
        val longest = maxOf(metrics.widthPixels, metrics.heightPixels)
        val scale = if (longest > maxDimension) maxDimension.toFloat() / longest else 1f
        client.startScreenShare((metrics.widthPixels * scale).toInt(), (metrics.heightPixels * scale).toInt())
    }

    private fun wireSession(peerId: String, signaling: CallSignaling) {
        if (sessions.containsKey(peerId)) return
        val c = ensureActiveClient()
        sessions[peerId] = signaling

        val isPrimary = primaryPeerId == null
        if (isPrimary) primaryPeerId = peerId
        val renderer = if (isPrimary) bubble.show(c.eglBase.eglBaseContext) else null
        c.createPeerConnection(peerId, renderer)

        signaling.observeOffer { sdp ->
            c.setRemoteDescription(peerId, sdp)
            c.createAnswer(peerId) { answerSdp ->
                scope.launch { runCatching { signaling.sendAnswer(answerSdp) } }
            }
        }
        signaling.observeIceCandidates(peerId) { c.addIceCandidate(peerId, it) }
        signaling.observeCallInfo { info ->
            if (info.state == CallState.ENDED) endSession(peerId)
        }
    }

    private fun endSession(peerId: String) {
        client?.closePeerConnection(peerId)
        sessions.remove(peerId)?.release()
        if (primaryPeerId == peerId) {
            primaryPeerId = null
            bubble.hide()
        }
        if (sessions.isEmpty()) {
            client?.stopLocalMedia()
        }
    }

    fun release() {
        hubSubscription?.let { sub -> runCatching { PeerHubHolder.getOrCreate(prefs).removeSubscriber(sub) } }
        sessions.values.forEach { it.release() }
        sessions.clear()
        primaryPeerId = null
        bubble.hide()
        client?.close()
        client = null
    }
}
