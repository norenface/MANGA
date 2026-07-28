package com.babycall.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Direct signaling between two viewers (a grandparent's phone and an
 * uncle's phone, say) so they can see and hear each other, not just the
 * baby. Every pair of viewers gets one of these, keyed by [pairKey] — the
 * two participants' device ids joined in sorted order, so both sides agree
 * on the same Firebase path without needing to coordinate first. By
 * convention the participant whose id sorts first is always the offerer;
 * the other side only ever answers. This avoids "glare" (both sides
 * creating an offer at once) without any extra negotiation messages.
 */
class MeshLinkRepository(familyId: String, private val pairKey: String) {

    private val db: FirebaseDatabase = Firebase.database
    private val linkRef: DatabaseReference =
        db.reference.child("families").child(familyId).child("call").child("mesh").child(pairKey)

    private var offerListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null
    private var iceListenerDeviceId: String? = null

    suspend fun sendOffer(sdp: SessionDescription) {
        linkRef.child("offer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
    }

    suspend fun sendAnswer(sdp: SessionDescription) {
        linkRef.child("answer").setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)).await()
    }

    fun observeOffer(onOffer: (SessionDescription) -> Unit) {
        offerListener?.let { linkRef.child("offer").removeEventListener(it) }
        val ref = linkRef.child("offer")
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

    fun observeAnswer(onAnswer: (SessionDescription) -> Unit) {
        answerListener?.let { linkRef.child("answer").removeEventListener(it) }
        val ref = linkRef.child("answer")
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

    suspend fun sendIceCandidate(fromDeviceId: String, candidate: IceCandidate) {
        linkRef.child("candidates").child(fromDeviceId).push().setValue(
            mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "sdp" to candidate.sdp
            )
        ).await()
    }

    fun observeIceCandidates(fromDeviceId: String, onCandidate: (IceCandidate) -> Unit) {
        iceListenerDeviceId?.let { prev -> iceListener?.let { linkRef.child("candidates").child(prev).removeEventListener(it) } }
        val ref = linkRef.child("candidates").child(fromDeviceId)
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

    fun release() {
        offerListener?.let { linkRef.child("offer").removeEventListener(it) }
        answerListener?.let { linkRef.child("answer").removeEventListener(it) }
        iceListenerDeviceId?.let { dev -> iceListener?.let { linkRef.child("candidates").child(dev).removeEventListener(it) } }
        offerListener = null
        answerListener = null
        iceListener = null
        iceListenerDeviceId = null
    }

    companion object {
        /** Deterministic path key for a pair of device ids — same regardless of which side computes it. */
        fun keyFor(a: String, b: String): String {
            val (first, second) = if (a < b) a to b else b to a
            return "${first}_$second"
        }
    }
}
