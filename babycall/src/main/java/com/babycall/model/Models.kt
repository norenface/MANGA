package com.babycall.model

/** Role a paired device plays inside a family. */
enum class Role {
    PARENT,
    BABY
}

/** Lifecycle of the shared call node under /families/{familyId}/call in Firebase RTDB. */
enum class CallState {
    IDLE,
    RINGING,
    CONNECTED,
    ENDED
}

fun CallState.wire(): String = name.lowercase()

fun String?.toCallState(): CallState = when (this) {
    "ringing" -> CallState.RINGING
    "connected" -> CallState.CONNECTED
    "ended" -> CallState.ENDED
    else -> CallState.IDLE
}

data class FamilyMember(
    val deviceId: String = "",
    val role: String = "",
    val name: String = "",
    val lastSeen: Long = 0L
)

data class CallSdp(
    val type: String = "",
    val sdp: String = ""
)

data class CallSession(
    val state: String = "idle",
    val callerId: String = "",
    val calleeId: String = "",
    val startedAt: Long = 0L
)
