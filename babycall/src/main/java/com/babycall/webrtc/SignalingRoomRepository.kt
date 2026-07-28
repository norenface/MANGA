package com.babycall.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * Baby-side (cloud mode only): watches every viewer's session under
 * /families/{familyId}/call/sessions instead of a single fixed peer, so
 * several relatives can each be in their own independent call with the baby
 * device at the same time. For the actual offer/answer/ICE plumbing of a
 * given session, construct a [SignalingRepository] scoped to that
 * sessionId — this class only tracks which sessions currently exist.
 */
class SignalingRoomRepository(familyId: String) {

    private val db: FirebaseDatabase = Firebase.database
    private val sessionsRef: DatabaseReference =
        db.reference.child("families").child(familyId).child("call").child("sessions")

    private var listener: ChildEventListener? = null
    private val activeSessionIds = mutableSetOf<String>()

    /**
     * @param onNewSession fired once per session that appears in "ringing"
     * state (including ones already ringing when this listener attaches).
     * @param onSessionEnded fired when a session is marked "ended" or is
     * removed outright — either way, the caller should tear down whatever
     * PeerConnection it made for that sessionId.
     */
    fun observeSessions(
        onNewSession: (sessionId: String, callerId: String) -> Unit,
        onSessionEnded: (sessionId: String) -> Unit
    ) {
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val sessionId = snapshot.key ?: return
                val state = snapshot.child("state").getValue(String::class.java)
                if (state == "ringing" && activeSessionIds.add(sessionId)) {
                    val callerId = snapshot.child("callerId").getValue(String::class.java).orEmpty()
                    onNewSession(sessionId, callerId)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val sessionId = snapshot.key ?: return
                val state = snapshot.child("state").getValue(String::class.java)
                if (state == "ended" && activeSessionIds.remove(sessionId)) {
                    onSessionEnded(sessionId)
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val sessionId = snapshot.key ?: return
                if (activeSessionIds.remove(sessionId)) {
                    onSessionEnded(sessionId)
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        sessionsRef.addChildEventListener(l)
        listener = l
    }

    fun release() {
        listener?.let { sessionsRef.removeEventListener(it) }
        listener = null
        activeSessionIds.clear()
    }
}
