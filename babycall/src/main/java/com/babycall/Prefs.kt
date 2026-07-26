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

    val isPaired: Boolean
        get() = !familyId.isNullOrEmpty() && !role.isNullOrEmpty()

    fun clearPairing() {
        sp.edit()
            .remove(KEY_ROLE)
            .remove(KEY_FAMILY_ID)
            .remove(KEY_PIN_HASH)
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

        fun hashPin(rawPin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("babycall:$rawPin").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
