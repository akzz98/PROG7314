package za.co.munipulse.auth

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LanguageCodes {
    const val ENGLISH = "en"
    const val ZULU = "zu"

    fun normalize(code: String?): String = if (code == ZULU) ZULU else ENGLISH
}

// UI language for the whole app. Kept outside the session file so sign-out does not reset it.
class AppLanguage(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): String = LanguageCodes.normalize(preferences.getString(KEY, null))

    fun save(code: String): Boolean {
        val next = LanguageCodes.normalize(code)
        if (preferences.getString(KEY, null) == next) {
            return false
        }
        preferences.edit().putString(KEY, next).apply()
        return true
    }

    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(read())
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private companion object {
        const val FILE = "munipulse_language"
        const val KEY = "code"
    }
}
