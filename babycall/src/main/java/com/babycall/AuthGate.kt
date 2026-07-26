package com.babycall

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Anonymous Firebase Auth sign-in. This is a low-cost improvement over fully
 * open database rules: it stops opportunistic scanners from reading/writing
 * an exposed Realtime Database with plain HTTP, since Firebase rules can
 * require `auth != null`. It is NOT strong access control (any installed
 * copy of this app, or Firebase's own client SDKs, can sign in anonymously
 * too) — real per-family isolation still relies on familyId being an
 * unguessable random key and pairing codes being short-lived + single-use.
 * See README-babycall.md for a stronger setup if you need it.
 */
object AuthGate {
    suspend fun ensureSignedIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }
}
