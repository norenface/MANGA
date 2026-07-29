package com.babycall.model

/** Lifecycle of one call connection. */
enum class CallState {
    IDLE,
    RINGING,
    CONNECTED,
    ENDED
}
