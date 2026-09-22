package za.co.munipulse.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import za.co.munipulse.auth.GoogleSignInViewModel
import za.co.munipulse.auth.SignInUiState
import za.co.munipulse.ui.login.LoginScreen
import za.co.munipulse.ui.login.SignedInScreen
import za.co.munipulse.ui.splash.SplashScreen

@Composable
fun MuniPulseApp(viewModel: GoogleSignInViewModel = viewModel()) {
    var showSplash by remember { mutableStateOf(true) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        viewModel.refresh()
        showSplash = false
        Log.i(TAG, "Left splash for Google sign-in")
    }

    if (showSplash || state is SignInUiState.Checking) {
        SplashScreen()
        return
    }

    when (val current = state) {
        is SignInUiState.SignedIn -> SignedInScreen(
            displayName = current.displayName,
            email = current.email,
            onSignOut = viewModel::signOut,
        )
        else -> LoginScreen(
            state = current,
            onGoogleSignIn = viewModel::signIn,
        )
    }
}

private const val SPLASH_MILLIS = 700L
private const val TAG = "MuniPulse"
