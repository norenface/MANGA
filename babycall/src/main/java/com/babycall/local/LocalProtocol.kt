package com.babycall.local

/** Shared constants for the local (same-Wi-Fi, no internet) transport. */
object LocalProtocol {
    /** mDNS service type the baby device advertises while ready to receive a call. */
    const val CALL_SERVICE_TYPE = "_babycall._tcp."

    /** mDNS service type the parent device advertises while showing a pairing code. */
    const val PAIRING_SERVICE_TYPE = "_babycallpair._tcp."

    fun callServiceName(familyId: String): String = "babycall-$familyId"
    fun pairingServiceName(sessionId: String): String = "babycallpair-$sessionId"

    // Call-time message types (newline-delimited JSON over a plain TCP socket).
    const val MSG_HELLO = "hello"
    const val MSG_HELLO_OK = "hello_ok"
    const val MSG_HELLO_REJECT = "hello_reject"
    const val MSG_CALL_START = "call_start"
    const val MSG_OFFER = "offer"
    const val MSG_ANSWER = "answer"
    const val MSG_ICE = "ice"
    const val MSG_END = "end"
    const val MSG_SETTINGS_UPDATE = "settings_update"
    const val MSG_UNPAIR = "unpair"

    // Pairing-time message types.
    const val MSG_REDEEM = "redeem"
    const val MSG_PAIRED = "paired"
    const val MSG_PAIR_REJECT = "pair_reject"
}
