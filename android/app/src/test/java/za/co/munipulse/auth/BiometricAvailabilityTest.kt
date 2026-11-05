package za.co.munipulse.auth

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricAvailabilityTest {
    @Test
    fun mapsPromptReadiness() {
        assertEquals(BiometricAvailability.Ready, biometricAvailability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(
            BiometricAvailability.NoneEnrolled,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertEquals(
            BiometricAvailability.Unavailable,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
        assertEquals(
            BiometricAvailability.Unavailable,
            biometricAvailability(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE),
        )
    }
}
