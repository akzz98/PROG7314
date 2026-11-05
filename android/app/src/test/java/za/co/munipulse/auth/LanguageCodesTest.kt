package za.co.munipulse.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageCodesTest {
    @Test
    fun keepsEnglishAndIsiZuluOnly() {
        assertEquals(LanguageCodes.ENGLISH, LanguageCodes.normalize(null))
        assertEquals(LanguageCodes.ENGLISH, LanguageCodes.normalize(""))
        assertEquals(LanguageCodes.ENGLISH, LanguageCodes.normalize("af"))
        assertEquals(LanguageCodes.ENGLISH, LanguageCodes.normalize(LanguageCodes.ENGLISH))
        assertEquals(LanguageCodes.ZULU, LanguageCodes.normalize(LanguageCodes.ZULU))
    }
}
