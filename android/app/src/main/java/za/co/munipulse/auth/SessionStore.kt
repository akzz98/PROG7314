package za.co.munipulse.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

// Encrypted on-device copy of the API JWT. The token itself is never logged.
// https://developer.android.com/topic/security/data
class SessionStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        FILE_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(session: SessionResponse, displayName: String, email: String, defaultWardCode: String) {
        val expiresAt = System.currentTimeMillis() + session.expiresIn * 1000L
        preferences.edit()
            .putString(KEY_TOKEN, session.accessToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, session.user.id)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_EMAIL, email)
            .putString(KEY_WARD, WardCatalog.normalize(defaultWardCode))
            .apply()
        Log.i(TAG, "Stored API session for user ${session.user.id}")
    }

    fun updateWard(code: String): Boolean {
        if (!WardCatalog.isKnown(code)) {
            return false
        }
        preferences.edit().putString(KEY_WARD, code).apply()
        return true
    }

    fun updateLanguage(code: String) {
        if (code != "en" && code != "zu") {
            return
        }
        preferences.edit().putString(KEY_LANGUAGE, code).apply()
    }

    fun readLanguage(): String? {
        val code = preferences.getString(KEY_LANGUAGE, null)
        return if (code == "en" || code == "zu") code else null
    }

    fun read(): StoredSession? {
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt <= 0L) {
            return null
        }
        return StoredSession(
            accessToken = token,
            expiresAtEpochMs = expiresAt,
            userId = userId,
            displayName = preferences.getString(KEY_DISPLAY_NAME, null).orEmpty(),
            email = preferences.getString(KEY_EMAIL, null).orEmpty(),
            defaultWardCode = WardCatalog.normalize(preferences.getString(KEY_WARD, null)),
        )
    }

    fun clear() {
        val hadSession = preferences.contains(KEY_TOKEN)
        preferences.edit().clear().apply()
        if (hadSession) {
            Log.i(TAG, "Cleared stored API session")
        }
    }

    private companion object {
        const val TAG = "MuniPulseAuth"
        const val FILE_NAME = "munipulse_session"
        const val KEY_TOKEN = "access_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_USER_ID = "user_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_EMAIL = "email"
        const val KEY_WARD = "default_ward"
        const val KEY_LANGUAGE = "preferred_language"
    }
}

data class StoredSession(
    val accessToken: String,
    val expiresAtEpochMs: Long,
    val userId: String,
    val displayName: String,
    val email: String,
    val defaultWardCode: String,
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        nowEpochMs >= expiresAtEpochMs - EXPIRY_SKEW_MS

    private companion object {
        const val EXPIRY_SKEW_MS = 60_000L
    }
}
