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
 * Transport-agnostic signaling channel for ONE participant's session in a
 * call. Implementations:
 *  - [com.babycall.webrtc.SignalingRepository] (Firebase Realtime Database,
 *    scoped to one session under /families/{familyId}/call/sessions/{id};
 *    works from anywhere with internet)
 *  - the `local.*` classes (raw socket over the home Wi-Fi/LAN, no internet
 *    or account needed, single session only, same-network only)
 *
 * A viewer (parent/relative) always owns exactly one CallSignaling for the
 * duration of a call. In cloud mode, the baby device owns one per connected
 * viewer simultaneously (see [SignalingRoomRepository]), so several people
 * can be in the same call independently of each other; in local mode the
 * baby is limited to a single CallSignaling (see LocalCallServer). Call
 * [release] exactly once when done with an instance.
 */
interface CallSignaling {
    fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit)
    fun observeOffer(onOffer: (SessionDescription) -> Unit)
    fun observeAnswer(onAnswer: (SessionDescription) -> Unit)
    fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit)

    suspend fun startCall(callerId: String, calleeId: String)
    suspend fun sendOffer(sdp: SessionDescription)
    suspend fun sendAnswer(sdp: SessionDescription)
    suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate)
    suspend fun endCall(endedBy: String)

    /** Stops all listeners and releases any sockets/connections. Idempotent. */
    fun release()
}
