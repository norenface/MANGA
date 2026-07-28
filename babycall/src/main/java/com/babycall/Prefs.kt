package com.babycall

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/**
 * Local device state. Nothing here is shared between devices except what we
 * explicitly write to Firebase (familyId + deviceId + role), so a baby device
 * has no way to discover or dial any other family's data.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("babycall_prefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = sp.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var role: String?
        get() = sp.getString(KEY_ROLE, null)
        set(value) = sp.edit().putString(KEY_ROLE, value).apply()

    var familyId: String?
        get() = sp.getString(KEY_FAMILY_ID, null)
        set(value) = sp.edit().putString(KEY_FAMILY_ID, value).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_DEVICE_NAME, value).apply()

    /** Hashed 4-6 digit PIN. Only the parent app ever prompts for the raw PIN. */
    var pinHash: String?
        get() = sp.getString(KEY_PIN_HASH, null)
        set(value) = sp.edit().putString(KEY_PIN_HASH, value).apply()

    var autoAnswer: Boolean
        get() = sp.getBoolean(KEY_AUTO_ANSWER, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_ANSWER, value).apply()

    /** "cloud" (Firebase, works anywhere) or "local" (same Wi-Fi only, no account/server needed). */
    var transportMode: String
        get() = sp.getString(KEY_TRANSPORT_MODE, TRANSPORT_CLOUD) ?: TRANSPORT_CLOUD
        set(value) = sp.edit().putString(KEY_TRANSPORT_MODE, value).apply()

    val isLocalMode: Boolean
        get() = transportMode == TRANSPORT_LOCAL

    /** Shared secret established at pairing time; local-mode devices reject any
     *  connection whose familyId+token don't match this, so a stranger's
     *  BabyCall install on the same Wi-Fi (e.g. an apartment building) can't
     *  connect in even if it discovers the mDNS service. */
    var localAuthToken: String?
        get() = sp.getString(KEY_LOCAL_AUTH_TOKEN, null)
        set(value) = sp.edit().putString(KEY_LOCAL_AUTH_TOKEN, value).apply()

    /** The other paired device's id, learned once at pairing time (local mode
     *  has no server-side member directory to look this up later). */
    var peerDeviceId: String?
        get() = sp.getString(KEY_PEER_DEVICE_ID, null)
        set(value) = sp.edit().putString(KEY_PEER_DEVICE_ID, value).apply()

    /** Baby device only: this device's own "ip:port" as last seen from the
     *  internet, if UPnP + STUN discovery succeeded on the current call
     *  server start. Null means online (cross-network) reach isn't
     *  available right now -- same-Wi-Fi calling still works either way. */
    var myPublicHost: String?
        get() = sp.getString(KEY_MY_PUBLIC_HOST, null)
        set(value) = sp.edit().putString(KEY_MY_PUBLIC_HOST, value).apply()

    /** Parent device only: the baby device's last known "ip:port" from the
     *  internet, learned the last time both devices were on the same Wi-Fi.
     *  Used as a fallback connection target when mDNS discovery finds
     *  nothing (i.e. the parent is out of the house). May go stale if the
     *  baby's home internet address changes before the next same-Wi-Fi sync. */
    var peerPublicHost: String?
        get() = sp.getString(KEY_PEER_PUBLIC_HOST, null)
        set(value) = sp.edit().putString(KEY_PEER_PUBLIC_HOST, value).apply()

    val isPaired: Boolean
        get() = !familyId.isNullOrEmpty() && !role.isNullOrEmpty()

    fun clearPairing() {
        sp.edit()
            .remove(KEY_ROLE)
            .remove(KEY_FAMILY_ID)
            .remove(KEY_PIN_HASH)
            .remove(KEY_TRANSPORT_MODE)
            .remove(KEY_LOCAL_AUTH_TOKEN)
            .remove(KEY_PEER_DEVICE_ID)
            .remove(KEY_MY_PUBLIC_HOST)
            .remove(KEY_PEER_PUBLIC_HOST)
            .apply()
    }

    fun checkPin(rawPin: String): Boolean {
        val stored = pinHash ?: return false
        return stored == hashPin(rawPin)
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ROLE = "role"
        private const val KEY_FAMILY_ID = "family_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_AUTO_ANSWER = "auto_answer"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_LOCAL_AUTH_TOKEN = "local_auth_token"
        private const val KEY_PEER_DEVICE_ID = "peer_device_id"
        private const val KEY_MY_PUBLIC_HOST = "my_public_host"
        private const val KEY_PEER_PUBLIC_HOST = "peer_public_host"

        const val TRANSPORT_CLOUD = "cloud"
        const val TRANSPORT_LOCAL = "local"

        fun hashPin(rawPin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("babycall:$rawPin").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
