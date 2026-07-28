package com.babycall.signaling

import com.babycall.model.CallState
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

data class RemoteCallInfo(
    val state: CallState,
    val callerId: String,
    val calleeId: String
)

/**
 * Transport-agnostic signaling channel used by CallActivity / CallListenerService.
 * Two implementations exist:
 *  - [com.babycall.webrtc.SignalingRepository] (Firebase Realtime Database, works
 *    from anywhere with internet)
 *  - the `local.*` classes (raw socket over the home Wi-Fi/LAN, no internet or
 *    account needed, but only works while both devices are on the same network)
 *
 * Each CallActivity/CallListenerService instance owns exactly one
 * CallSignaling and calls [release] exactly once when done with it.
 */
interface CallSignaling {
    fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit)
    fun observeOffer(onOffer: (SessionDescription) -> Unit)
    fun observeAnswer(onAnswer: (SessionDescription) -> Unit)
    fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit)

    /** True if the baby device is already in another call (e.g. a second viewer trying to call at the same time). */
    suspend fun isBusy(): Boolean

    suspend fun startCall(callerId: String, calleeId: String)
    suspend fun sendOffer(sdp: SessionDescription)
    suspend fun sendAnswer(sdp: SessionDescription)
    suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate)
    suspend fun endCall(endedBy: String)

    /** Stops all listeners and releases any sockets/connections. Idempotent. */
    fun release()
}
