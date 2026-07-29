package com.babycall.peer

import com.babycall.model.CallState
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.RemoteCallInfo
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class PeerConnectFailedException(message: String) : Exception(message)

/**
 * Viewer-side (parent, or an invited relative), persistent for the duration
 * of one call: a single broker connection multiplexing the call-to-baby leg
 * (implements [CallSignaling] directly, used exactly like the old
 * per-session SignalingRepository) AND direct viewer-to-viewer mesh legs
 * (see [meshSendOffer] etc., used via [PeerMeshLink]), since a broker peer id
 * can only be registered from one connection at a time. Mesh peers are
 * learned from the baby hub's roster_add/roster_remove messages (see
 * [observeRoster]) instead of a live database query.
 */
class PeerViewerConnection(
    familyCode: String,
    myPeerId: String
) : CallSignaling {

    private val hubId = PeerProtocol.hubPeerId(familyCode)
    private val ready = CompletableDeferred<Unit>()

    private var callInfoCb: ((RemoteCallInfo) -> Unit)? = null
    private var answerCb: ((SessionDescription) -> Unit)? = null
    private var iceCb: ((IceCandidate) -> Unit)? = null

    private var onRosterAdd: ((String) -> Unit)? = null
    private var onRosterRemove: ((String) -> Unit)? = null

    private val meshOfferCb = mutableMapOf<String, (SessionDescription) -> Unit>()
    private val meshAnswerCb = mutableMapOf<String, (SessionDescription) -> Unit>()
    private val meshIceCb = mutableMapOf<String, (IceCandidate) -> Unit>()

    private val client: PeerBrokerClient = PeerBrokerClient(
        myPeerId = myPeerId,
        onOpen = { ready.complete(Unit) },
        onAppMessage = { src, payload -> handleMessage(src, payload) },
        onFatalError = { message ->
            if (!ready.isCompleted) ready.completeExceptionally(PeerConnectFailedException(message))
            callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", ""))
        }
    )

    init {
        client.connect()
    }

    private fun handleMessage(src: String, payload: JSONObject) {
        when (payload.optString("appType")) {
            PeerProtocol.APP_CALL_ANSWER ->
                answerCb?.invoke(SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp")))
            PeerProtocol.APP_CALL_ICE ->
                iceCb?.invoke(IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.optString("sdp")))
            PeerProtocol.APP_CALL_END ->
                callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", ""))
            PeerProtocol.APP_PEER_LEFT -> {
                if (src == hubId) {
                    callInfoCb?.invoke(RemoteCallInfo(CallState.ENDED, "", ""))
                } else {
                    onRosterRemove?.invoke(src)
                }
            }
            PeerProtocol.APP_ROSTER_ADD ->
                payload.optString("peerId").takeIf { it.isNotEmpty() }?.let { onRosterAdd?.invoke(it) }
            PeerProtocol.APP_ROSTER_REMOVE ->
                payload.optString("peerId").takeIf { it.isNotEmpty() }?.let { onRosterRemove?.invoke(it) }
            PeerProtocol.APP_MESH_OFFER ->
                meshOfferCb[src]?.invoke(SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp")))
            PeerProtocol.APP_MESH_ANSWER ->
                meshAnswerCb[src]?.invoke(SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp")))
            PeerProtocol.APP_MESH_ICE ->
                meshIceCb[src]?.invoke(IceCandidate(payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.optString("sdp")))
            PeerProtocol.APP_MESH_END -> onRosterRemove?.invoke(src)
        }
    }

    // ---- CallSignaling: the call-to-baby leg ----

    override fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit) {
        callInfoCb = onChange
    }

    override fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        // The viewer only ever sends an offer; it never receives one from the baby.
    }

    override fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        answerCb = onAnswer
    }

    override fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceCb = onCandidate
    }

    override suspend fun startCall(callerId: String, calleeId: String) {
        ready.await()
        client.send(hubId, JSONObject().put("appType", PeerProtocol.APP_CALL_START).put("callerId", callerId))
    }

    override suspend fun sendOffer(sdp: SessionDescription) {
        client.send(hubId, JSONObject().put("appType", PeerProtocol.APP_CALL_OFFER).put("sdp", sdp.description))
    }

    override suspend fun sendAnswer(sdp: SessionDescription) {
        // The viewer never answers; it only calls the baby.
    }

    override suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        client.send(
            hubId,
            JSONObject()
                .put("appType", PeerProtocol.APP_CALL_ICE)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
        )
    }

    override suspend fun endCall(endedBy: String) {
        client.send(hubId, JSONObject().put("appType", PeerProtocol.APP_CALL_END))
    }

    override fun release() {
        callInfoCb = null
        answerCb = null
        iceCb = null
        onRosterAdd = null
        onRosterRemove = null
        meshOfferCb.clear()
        meshAnswerCb.clear()
        meshIceCb.clear()
        client.close()
    }

    // ---- Roster: learn about other connected viewers to mesh-connect to ----

    fun observeRoster(onAdd: (peerId: String) -> Unit, onRemove: (peerId: String) -> Unit) {
        onRosterAdd = onAdd
        onRosterRemove = onRemove
    }

    // ---- Mesh: direct viewer-to-viewer signaling, addressed by peer id (never touches the hub) ----

    fun meshSendOffer(otherPeerId: String, sdp: SessionDescription) {
        client.send(otherPeerId, JSONObject().put("appType", PeerProtocol.APP_MESH_OFFER).put("sdp", sdp.description))
    }

    fun meshSendAnswer(otherPeerId: String, sdp: SessionDescription) {
        client.send(otherPeerId, JSONObject().put("appType", PeerProtocol.APP_MESH_ANSWER).put("sdp", sdp.description))
    }

    fun meshSendIceCandidate(otherPeerId: String, candidate: IceCandidate) {
        client.send(
            otherPeerId,
            JSONObject()
                .put("appType", PeerProtocol.APP_MESH_ICE)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("sdp", candidate.sdp)
        )
    }

    fun meshObserveOffer(otherPeerId: String, onOffer: (SessionDescription) -> Unit) {
        meshOfferCb[otherPeerId] = onOffer
    }

    fun meshObserveAnswer(otherPeerId: String, onAnswer: (SessionDescription) -> Unit) {
        meshAnswerCb[otherPeerId] = onAnswer
    }

    fun meshObserveIceCandidates(otherPeerId: String, onCandidate: (IceCandidate) -> Unit) {
        meshIceCb[otherPeerId] = onCandidate
    }

    fun meshRelease(otherPeerId: String) {
        meshOfferCb.remove(otherPeerId)
        meshAnswerCb.remove(otherPeerId)
        meshIceCb.remove(otherPeerId)
    }
}

/**
 * Adapter matching the old Firebase-based MeshLinkRepository's method shape
 * exactly, so CallActivity's mesh-wiring code is unchanged: delegates to the
 * viewer's single shared [PeerViewerConnection], bound to one [otherPeerId].
 */
class PeerMeshLink(private val connection: PeerViewerConnection, private val otherPeerId: String) {
    fun sendOffer(sdp: SessionDescription) = connection.meshSendOffer(otherPeerId, sdp)
    fun sendAnswer(sdp: SessionDescription) = connection.meshSendAnswer(otherPeerId, sdp)
    fun observeOffer(onOffer: (SessionDescription) -> Unit) = connection.meshObserveOffer(otherPeerId, onOffer)
    fun observeAnswer(onAnswer: (SessionDescription) -> Unit) = connection.meshObserveAnswer(otherPeerId, onAnswer)
    fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) = connection.meshSendIceCandidate(otherPeerId, candidate)
    fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) = connection.meshObserveIceCandidates(otherPeerId, onCandidate)
    fun release() = connection.meshRelease(otherPeerId)
}
