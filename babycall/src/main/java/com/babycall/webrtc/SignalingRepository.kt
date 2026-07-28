package com.babycall.webrtc

import com.babycall.model.CallState
import com.babycall.model.toCallState
import com.babycall.signaling.CallSignaling
import com.babycall.signaling.RemoteCallInfo
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.Firebase
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * All WebRTC signaling (offer/answer/ICE exchange) goes through
 * /families/{familyId}/call in Firebase Realtime Database. This node is only
 * ever readable/writable by devices that belong to that family (see
 * database.rules.json in README-babycall.md), so signaling never leaks
 * across families and a baby device can't be reached by anyone else's app.
 */
class SignalingRepository(private val familyId: String) : CallSignaling {

    private val db: FirebaseDatabase = Firebase.database
    private val callRef: DatabaseReference = db.reference.child("families").child(familyId).child("call")

    private var callInfoListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null
    private var iceListenerDeviceId: String? = null

    override fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit) {
        callInfoListener?.let { callRef.removeEventListener(it) }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = snapshot.child("state").getValue(String::class.java).toCallState()
                val callerId = snapshot.child("callerId").getValue(String::class.java).orEmpty()
                val calleeId = snapshot.child("calleeId").getValue(String::class.java).orEmpty()
                onChange(RemoteCallInfo(state, callerId, calleeId))
            }

            override fun onCancelled(error: DatabaseError) { /* listener stays registered; next tick retries */ }
        }
        callRef.addValueEventListener(listener)
        callInfoListener = listener
    }

    override suspend fun isBusy(): Boolean {
        val state = callRef.child("state").get().await().getValue(String::class.java).toCallState()
        return state == CallState.RINGING || state == CallState.CONNECTED
    }

    override suspend fun startCall(callerId: String, calleeId: String) {
        callRef.child("candidates").removeValue().await()
        callRef.child("offer").removeValue().await()
        callRef.child("answer").removeValue().await()
        callRef.setValue(
            mapOf(
                "state" to "ringing",
                "callerId" to callerId,
                "calleeId" to calleeId,
                "startedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun sendOffer(sdp: SessionDescription) {
        callRef.child("offer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
    }

    override suspend fun sendAnswer(sdp: SessionDescription) {
        callRef.child("answer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
        callRef.child("state").setValue("connected").await()
    }

    override fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        offerListener?.let { callRef.child("offer").removeEventListener(it) }
        val ref = callRef.child("offer")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                onOffer(SessionDescription(SessionDescription.Type.OFFER, sdp))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        offerListener = listener
    }

    override fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        answerListener?.let { callRef.child("answer").removeEventListener(it) }
        val ref = callRef.child("answer")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                onAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        answerListener = listener
    }

    override suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        callRef.child("candidates").child(fromDeviceId).push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )
        ).await()
    }

    override fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceListenerDeviceId?.let { prev -> iceListener?.let { callRef.child("candidates").child(prev).removeEventListener(it) } }
        val ref = callRef.child("candidates").child(fromDeviceId)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                onCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        iceListener = listener
        iceListenerDeviceId = fromDeviceId
    }

    override suspend fun endCall(endedBy: String) {
        callRef.child("state").setValue("ended").await()
        callRef.child("endedBy").setValue(endedBy).await()
    }

    suspend fun resetToIdle() {
        callRef.setValue(mapOf("state" to "idle")).await()
    }

    override fun release() {
        callInfoListener?.let { callRef.removeEventListener(it) }
        offerListener?.let { callRef.child("offer").removeEventListener(it) }
        answerListener?.let { callRef.child("answer").removeEventListener(it) }
        iceListenerDeviceId?.let { dev -> iceListener?.let { callRef.child("candidates").child(dev).removeEventListener(it) } }
        callInfoListener = null
        offerListener = null
        answerListener = null
        iceListener = null
        iceListenerDeviceId = null
    }
}
