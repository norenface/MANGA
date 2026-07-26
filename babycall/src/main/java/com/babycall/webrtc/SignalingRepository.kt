package com.babycall.webrtc

import com.babycall.model.CallState
import com.babycall.model.toCallState
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

data class RemoteCallInfo(
    val state: CallState,
    val callerId: String,
    val calleeId: String
)

/**
 * All WebRTC signaling (offer/answer/ICE exchange) goes through
 * /families/{familyId}/call in Firebase Realtime Database. This node is only
 * ever readable/writable by devices that belong to that family (see
 * database.rules.json in README-babycall.md), so signaling never leaks
 * across families and a baby device can't be reached by anyone else's app.
 */
class SignalingRepository(private val familyId: String) {

    private val db: FirebaseDatabase = Firebase.database
    private val callRef: DatabaseReference = db.reference.child("families").child(familyId).child("call")

    fun observeCallInfo(onChange: (RemoteCallInfo) -> Unit): ValueEventListener {
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
        return listener
    }

    fun removeCallInfoListener(listener: ValueEventListener) {
        callRef.removeEventListener(listener)
    }

    suspend fun startCall(callerId: String, calleeId: String) {
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

    suspend fun sendOffer(sdp: SessionDescription) {
        callRef.child("offer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
    }

    suspend fun sendAnswer(sdp: SessionDescription) {
        callRef.child("answer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
        callRef.child("state").setValue("connected").await()
    }

    fun observeOffer(onOffer: (SessionDescription) -> Unit): ValueEventListener {
        val ref = callRef.child("offer")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                onOffer(SessionDescription(SessionDescription.Type.OFFER, sdp))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun observeAnswer(onAnswer: (SessionDescription) -> Unit): ValueEventListener {
        val ref = callRef.child("answer")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                onAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun removeOfferListener(listener: ValueEventListener) = callRef.child("offer").removeEventListener(listener)
    fun removeAnswerListener(listener: ValueEventListener) = callRef.child("answer").removeEventListener(listener)

    suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        callRef.child("candidates").child(fromDeviceId).push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )
        ).await()
    }

    fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit): ChildEventListener {
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
        return listener
    }

    fun removeIceCandidatesListener(fromDeviceId: String, listener: ChildEventListener) {
        callRef.child("candidates").child(fromDeviceId).removeEventListener(listener)
    }

    suspend fun endCall(endedBy: String) {
        callRef.child("state").setValue("ended").await()
        callRef.child("endedBy").setValue(endedBy).await()
    }

    suspend fun resetToIdle() {
        callRef.setValue(mapOf("state" to "idle")).await()
    }
}
