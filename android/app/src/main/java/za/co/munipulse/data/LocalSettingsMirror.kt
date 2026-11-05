package za.co.munipulse.data

import android.content.Context
import android.util.Log
import za.co.munipulse.auth.AppLanguage
import za.co.munipulse.auth.BiometricStore
import za.co.munipulse.auth.LanguageCodes

// Copies the current device settings into Room. lastSyncAt is left as it is.
class LocalSettingsMirror(context: Context) {
    private val language = AppLanguage(context)
    private val biometric = BiometricStore(context)
    private val settings = MuniPulseDatabase.get(context).localSettings()

    suspend fun refresh() {
        val existing = settings.read()
        val code = LanguageCodes.normalize(language.read())
        settings.save(
            LocalSettings(
                biometricEnabled = biometric.read(),
                lastSyncAt = existing?.lastSyncAt,
                language = code,
            ),
        )
        Log.i(TAG, "Settings mirror saved. Language $code")
    }

    private companion object {
        const val TAG = "MuniPulseSync"
    }
}
