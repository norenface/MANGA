package com.babycall.peer

import java.security.SecureRandom

/**
 * App-level message vocabulary and peer-id naming for the peer-broker
 * ("online") transport. This sits on top of [PeerBrokerClient], which only
 * speaks the PeerJS relay's own wire protocol -- everything here is our own
 * opaque payload the relay never looks at.
 */
object PeerProtocol {
    private val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I

    /**
     * A family's one durable code: typed into the baby device once to pair
     * it, and shared with relatives any number of times afterwards to let
     * them join as viewers. Unlike the old Firebase design there is no
     * separate database record for a family -- this code IS the family's
     * address on the broker (see [hubPeerId]), so redeeming or joining both
     * require the baby device to be online and connected at that moment.
     */
    fun randomFamilyCode(): String {
        val sb = StringBuilder()
        repeat(10) { sb.append(CODE_CHARS[SecureRandom().nextInt(CODE_CHARS.length)]) }
        return sb.toString()
    }

    fun normalizeCode(raw: String): String =
        raw.trim().uppercase().filter { it.isLetterOrDigit() }

    /** The baby device's stable, well-known peer id for a family -- anyone who knows the code can address it. */
    fun hubPeerId(familyCode: String): String = "bch-" + normalizeCode(familyCode)

    /** A fresh, unpredictable peer id for a short-lived connection (pairing, one-shot settings pushes, ...). */
    fun randomPeerId(prefix: String): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return "$prefix-" + bytes.joinToString("") { "%02x".format(it) }
    }

    // Pairing: baby <-> the parent device temporarily hosting the family's hub id.
    const val APP_PAIR_REDEEM_REQUEST = "pair_redeem_request"
    const val APP_PAIR_REDEEM_RESPONSE = "pair_redeem_response"

    // Joining as an additional viewer: any parent-role device <-> the baby's hub.
    const val APP_INVITE_JOIN_REQUEST = "invite_join_request"
    const val APP_INVITE_JOIN_RESPONSE = "invite_join_response"

    // Settings pushed from a parent-role device to the baby's hub.
    const val APP_SETTINGS_SET = "settings_set"
    const val APP_SETTINGS_SET_ACK = "settings_set_ack"

    // Unpairing.
    const val APP_UNPAIR_BABY = "unpair_baby" // parent -> hub: forget this family entirely
    const val APP_UNPAIR_BABY_ACK = "unpair_baby_ack"
    const val APP_UNPAIR_NOTIFY = "unpair_notify" // hub -> connected viewers: it's gone, disconnect

    // Call signaling: viewer <-> baby hub, one logical session per viewer.
    const val APP_CALL_START = "call_start"
    const val APP_CALL_OFFER = "call_offer"
    const val APP_CALL_ANSWER = "call_answer"
    const val APP_CALL_ICE = "call_ice"
    const val APP_CALL_END = "call_end"

    // Roster: hub -> viewers, so viewers can mesh-connect directly to each other.
    const val APP_ROSTER_ADD = "roster_add"
    const val APP_ROSTER_REMOVE = "roster_remove"

    // Mesh: viewer <-> viewer directly (never touches the hub).
    const val APP_MESH_OFFER = "mesh_offer"
    const val APP_MESH_ANSWER = "mesh_answer"
    const val APP_MESH_ICE = "mesh_ice"
    const val APP_MESH_END = "mesh_end"

    /** Synthetic appType [PeerBrokerClient] generates locally from a wire-level LEAVE frame (the other side's socket closed). */
    const val APP_PEER_LEFT = "peer_left"
}
