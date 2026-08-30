package com.dqauto

import android.content.Context
import android.content.SharedPreferences

/**
 * Local device state only. The login ID/password are never written anywhere
 * but this device's own SharedPreferences -- they must never end up in the
 * app's source code or be committed to the repository.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("dqauto_prefs", Context.MODE_PRIVATE)

    var loginId: String?
        get() = sp.getString(KEY_LOGIN_ID, null)
        set(value) = sp.edit().putString(KEY_LOGIN_ID, value).apply()

    var loginPassword: String?
        get() = sp.getString(KEY_LOGIN_PASSWORD, null)
        set(value) = sp.edit().putString(KEY_LOGIN_PASSWORD, value).apply()

    /** Seconds to wait after completing one full cycle before starting the next. */
    var cycleIntervalSeconds: Int
        get() = sp.getInt(KEY_CYCLE_INTERVAL_SECONDS, DEFAULT_CYCLE_INTERVAL_SECONDS)
        set(value) = sp.edit().putInt(KEY_CYCLE_INTERVAL_SECONDS, value).apply()

    val hasLogin: Boolean
        get() = !loginId.isNullOrEmpty() && !loginPassword.isNullOrEmpty()

    fun clearLogin() {
        sp.edit().remove(KEY_LOGIN_ID).remove(KEY_LOGIN_PASSWORD).apply()
    }

    companion object {
        private const val KEY_LOGIN_ID = "login_id"
        private const val KEY_LOGIN_PASSWORD = "login_password"
        private const val KEY_CYCLE_INTERVAL_SECONDS = "cycle_interval_seconds"

        const val DEFAULT_CYCLE_INTERVAL_SECONDS = 30
    }
}
