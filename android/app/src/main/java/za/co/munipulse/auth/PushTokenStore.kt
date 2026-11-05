package za.co.munipulse.auth

import android.content.Context

// Local copy of the FCM registration id. The token itself is never logged.
class PushTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): String? = preferences.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(token: String) {
        preferences.edit().putString(KEY, token).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val FILE = "munipulse_fcm"
        const val KEY = "token"
    }
}
