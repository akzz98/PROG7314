package za.co.munipulse.ui.login

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import za.co.munipulse.R
import za.co.munipulse.auth.BiometricAvailability
import za.co.munipulse.auth.BiometricUnlock

@Composable
fun BiometricGateScreen(
    working: Boolean,
    message: String,
    onUnlocked: () -> Unit,
    onUseGoogle: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val availability = remember(activity) {
        if (activity == null) BiometricAvailability.Unavailable else BiometricUnlock.availability(activity)
    }
    val promptTitle = stringResource(R.string.biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.biometric_prompt_subtitle)
    val promptCancel = stringResource(R.string.biometric_prompt_cancel)
    val cancelled = stringResource(R.string.biometric_cancelled)
    val failed = stringResource(R.string.biometric_failed)
    val none = stringResource(R.string.biometric_none)
    val unavailable = stringResource(R.string.biometric_unavailable)
    val lockout = stringResource(R.string.biometric_lockout)
    var attempts by remember { mutableIntStateOf(0) }

    fun launchPrompt() {
        val host = activity
        if (host == null) {
            onMessage(unavailable)
            return
        }
        when (BiometricUnlock.availability(host)) {
            BiometricAvailability.NoneEnrolled -> onMessage(none)
            BiometricAvailability.Unavailable -> onMessage(unavailable)
            BiometricAvailability.Ready -> {
                Log.i(TAG, "Biometric prompt opened")
                BiometricUnlock.showPrompt(
                    activity = host,
                    title = promptTitle,
                    subtitle = promptSubtitle,
                    negativeButton = promptCancel,
                    onSuccess = {
                        Log.i(TAG, "Biometric prompt succeeded")
                        onUnlocked()
                    },
                    onError = { code ->
                        Log.i(TAG, "Biometric prompt closed. Code $code")
                        onMessage(
                            when (code) {
                                BiometricPrompt.ERROR_LOCKOUT,
                                BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                                -> lockout
                                BiometricPrompt.ERROR_NO_BIOMETRICS -> none
                                BiometricPrompt.ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                                -> unavailable
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_CANCELED,
                                -> cancelled
                                else -> failed
                            },
                        )
                    },
                )
            }
        }
    }

    LaunchedEffect(availability) {
        Log.i(TAG, "Biometric gate shown. Availability $availability")
        if (availability == BiometricAvailability.Ready && attempts == 0) {
            attempts = 1
            launchPrompt()
        } else if (availability == BiometricAvailability.NoneEnrolled) {
            onMessage(none)
        } else if (availability == BiometricAvailability.Unavailable) {
            onMessage(unavailable)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.biometric_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        if (working) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { launchPrompt() },
                enabled = availability == BiometricAvailability.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.biometric_use))
            }
            TextButton(onClick = onUseGoogle, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.biometric_google))
            }
        }
        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val TAG = "MuniPulseAuth"
