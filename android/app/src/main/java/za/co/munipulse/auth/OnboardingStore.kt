package za.co.munipulse.auth

import android.content.Context

// First-run gate. Kept out of the encrypted session file so sign-out does not show it again.
// COMPLETE_VERSION rises when the three onboarding pages replace this gate, so an earlier
// continue does not hide those pages.
class OnboardingStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = preferences.getInt(KEY_VERSION, 0) >= COMPLETE_VERSION

    fun markComplete() {
        preferences.edit().putInt(KEY_VERSION, COMPLETE_VERSION).apply()
    }

    private companion object {
        const val FILE_NAME = "munipulse_onboarding"
        const val KEY_VERSION = "complete_version"
        const val COMPLETE_VERSION = 1
    }
}
