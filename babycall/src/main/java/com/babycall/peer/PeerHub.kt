package com.babycall.peer

import com.babycall.Prefs
import com.babycall.model.CallState
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.RemoteCallInfo
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/** One subscription to [PeerHub.observeSessions]; pass back to [PeerHub.removeSubscriber] to stop it. */
class PeerSessionSubscription internal constructor(
    internal val onNewSession: (sessionId: String, signaling: CallSignaling) -> Unit,
    internal val onSessionEnded: (sessionId: String) -> Unit
)

/**
 * Baby-side, persistent: the one broker connection for this family, held for
 * as long as [com.babycall.call.CallListenerService] is running. Replaces
 * both [com.babycall.webrtc.SignalingRoomRepository] (multi-viewer session
 * tracking) and per-session [com.babycall.webrtc.SignalingRepository]
 * instances, plus the settings/unpair parts of the old Firebase
 * PairingRepository -- all multiplexed over this one WebSocket, since a
 * broker peer id can only be registered from one connection at a time.
 *
 * Every currently-connected viewer is told about every other one (see
 * [broadcastRosterAdd]/[broadcastRosterRemove]) so they can mesh-connect
 * directly to each other, mirroring what a live Firebase query used to do
 * for free.
 */
class PeerHub(private val prefs: Prefs) {

    /** Baby device was unpaired remotely (parent's "unpair" from Settings). */
    var onUnpaired: (() -> Unit)? = null

    private var client: PeerBrokerClient? = null
    private val activeSessions = mutableMapOf<String, PeerSessionSignaling>()
    private val subscribers = mutableListOf<PeerSessionSubscription>()

    fun start() {
        if (client != null) return
        val familyId = prefs.familyId ?: return
        val c = PeerBrokerClient(
            myPeerId = PeerProtocol.hubPeerId(familyId),
            onOpen = {},
            onAppMessage = { src, payload -> handleMessage(src, payload) },
            onFatalError = { /* best-effort; broker reconnect isn't implemented, next call attempt will just fail clearly */ }
        )
        client = c
        c.connect()
    }

    fun stop() {
        client?.close()
        client = null
        activeSessions.values.forEach { it.release() }
        activeSessions.clear()
        subscribers.clear()
    }

    /**
     * @param onNewSession fired once per currently-connected viewer
     * (replayed immediately for any that already exist) and again for each
     * new one that connects afterwards.
     * @param onSessionEnded fired when a viewer's session ends or drops.
     */
    fun observeSessions(
        onNewSession: (sessionId: String, signaling: CallSignaling) -> Unit,
        onSessionEnded: (sessionId: String) -> Unit
    ): PeerSessionSubscription {
        val sub = PeerSessionSubscription(onNewSession, onSessionEnded)
        subscribers.add(sub)
        activeSessions.forEach { (sessionId, signaling) -> onNewSession(sessionId, signaling) }
        return sub
    }

    fun removeSubscriber(subscription: PeerSessionSubscription) {
        subscribers.remove(subscription)
    }

    private fun handleMessage(src: String, payload: JSONObject) {
        when (payload.optString("appType")) {
            PeerProtocol.APP_CALL_START -> {
                if (!activeSessions.containsKey(src)) {
                    val signaling = PeerSessionSignaling(requireClient(), src, prefs.deviceId)
                    activeSessions[src] = signaling
                    subscribers.toList().forEach { it.onNewSession(src, signaling) }
                    broadcastRosterAdd(newPeerId = src)
                }
            }
            PeerProtocol.APP_CALL_OFFER, PeerProtocol.APP_CALL_ICE -> {
                activeSessions[src]?.handleIncoming(payload)
            }
            PeerProtocol.APP_CALL_END, PeerProtocol.APP_PEER_LEFT -> {
                activeSessions[src]?.let { signaling ->
                    signaling.handleIncoming(payload)
                    activeSessions.remove(src)
                    subscribers.toList().forEach { it.onSessionEnded(src) }
                    broadcastRosterRemove(leftPeerId = src)
                }
            }
            PeerProtocol.APP_INVITE_JOIN_REQUEST -> {
                requireClient().send(src, JSONObject().put("appType", PeerProtocol.APP_INVITE_JOIN_RESPONSE).put("ok", true))
            }
            PeerProtocol.APP_SETTINGS_SET -> {
                if (payload.has("pinHash")) prefs.pinHash = payload.optString("pinHash").ifEmpty { null }
                if (payload.has("autoAnswer")) prefs.autoAnswer = payload.optBoolean("autoAnswer", prefs.autoAnswer)
                requireClient().send(src, JSONObject().put("appType", PeerProtocol.APP_SETTINGS_SET_ACK))
            }
            PeerProtocol.APP_UNPAIR_BABY -> {
                requireClient().send(src, JSONObject().put("appType", PeerProtocol.APP_UNPAIR_BABY_ACK))
                onUnpaired?.invoke()
            }
        }
    }

    private fun broadcastRosterAdd(newPeerId: String) {
        val c = requireClient()
        for ((otherId, _) in activeSessions) {
            if (otherId == newPeerId) continue
            c.send(otherId, JSONObject().put("appType", PeerProtocol.APP_ROSTER_ADD).put("peerId", newPeerId))
            c.send(newPeerId, JSONObject().put("appType", PeerProtocol.APP_ROSTER_ADD).put("peerId", otherId))
        }
    }

    private fun broadcastRosterRemove(leftPeerId: String) {
        val c = requireClient()
        for ((otherId, _) in activeSessions) {
            c.send(otherId, JSONObject().put("appType", PeerProtocol.APP_ROSTER_REMOVE).put("peerId", leftPeerId))
        }
    }

    private fun requireClient(): PeerBrokerClient = client ?: error("PeerHub not started")
}

/** One connected viewer's baby-side call leg. Created and owned by [PeerHub]. */
internal class PeerSessionSignaling(
    private val client: PeerBrokerClient,
    private val counterpartId: String,
    private val myDeviceId: String
) : CallSignaling {

    private var callInfoCb: ((RemoteCallInfo) -> Unit)? = null
    private var offerCb: ((SessionDescription) -> Unit)? = null
    private var iceCb: ((IceCandidate) -> Unit)? = null

    override fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit) {
        callInfoCb = onChange
    }

    override fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        offerCb = onOffer
    }

    override fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        // The baby device only ever answers; it never receives an answer.
    }

    override fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceCb = onCandidate
    }

    override suspend fun startCall(callerId: String, calleeId: String) {
        // The baby device never originates a call in this design.
    }

    override suspend fun sendOffer(sdp: SessionDescription) {
        // The baby device never sends an offer.
    }

    override suspend fun sendAnswer(sdp: SessionDescription) {
        client.send(counterpartId, JSONObject().put("appType", PeerProtocol.APP_CALL_ANSWER).put("sdp", sdp.description))
        callInfoCb?.invoke(RemoteCallInfo(CallState.CONNECTED, counterpartId, myDeviceId))
    }

    override suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        client.send(
            counterpartId,
            JSONObject()
                .put("appType", PeerProtocol.APP_CALL_ICE)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
        )
    }

    override suspend fun endCall(endedBy: String) {
        client.send(counterpartId, JSONObject().put("appType", PeerProtocol.APP_CALL_END))
    }

    override fun release() {
        callInfoCb = null
        offerCb = null
        iceCb = null
    }

    internal fun handleIncoming(payload: JSONObject) {
        when (payload.optString("appType")) {
            PeerProtocol.APP_CALL_OFFER -> {
                val sdp = payload.optString("sdp")
                offerCb?.invoke(SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            PeerProtocol.APP_CALL_ICE -> {
                val sdpMid = payload.optString("sdpMid")
                val sdpMLineIndex = payload.optInt("sdpMLineIndex")
                val sdp = payload.optString("sdp")
                iceCb?.invoke(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
            PeerProtocol.APP_CALL_END, PeerProtocol.APP_PEER_LEFT -> {
                callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", myDeviceId))
            }
        }
    }
}
