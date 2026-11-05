package za.co.munipulse.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

// https://developer.android.com/identity/sign-in/biometric-auth
enum class BiometricAvailability {
    Ready,
    NoneEnrolled,
    Unavailable,
}

fun biometricAvailability(status: Int): BiometricAvailability = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Ready
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NoneEnrolled
    else -> BiometricAvailability.Unavailable
}

class BiometricStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun save(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val FILE = "munipulse_biometric"
        const val KEY_ENABLED = "enabled"
    }
}

object BiometricUnlock {
    fun availability(context: Context): BiometricAvailability {
        val status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return biometricAvailability(status)
    }

    fun showPrompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String,
        onSuccess: () -> Unit,
        onError: (Int) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButton)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }
}
