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
import za.co.munipulse.ui.home.HomeScreen
import za.co.munipulse.ui.login.LoginScreen
import za.co.munipulse.ui.onboarding.OnboardingScreen
import za.co.munipulse.ui.settings.NotificationPreferencesScreen
import za.co.munipulse.ui.settings.SettingsScreen
import za.co.munipulse.ui.splash.SplashScreen

@Composable
fun MuniPulseApp(viewModel: GoogleSignInViewModel = viewModel()) {
    var splashHold by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val restoring by viewModel.restoring.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val onboardingDone by viewModel.onboardingComplete.collectAsState()
    val showSplash = splashHold || state is SignInUiState.Checking || restoring

    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        viewModel.refresh()
        splashHold = false
    }

    LaunchedEffect(showSplash) {
        if (!showSplash) {
            val route = when {
                state is SignInUiState.SignedIn -> "home"
                !onboardingDone -> "onboarding"
                else -> "login"
            }
            Log.i(TAG, "Splash routed to $route")
        }
    }

    val current = state
    when {
        showSplash -> SplashScreen()
        current is SignInUiState.SignedIn && showNotifications -> NotificationPreferencesScreen(
            preferences = notifications,
            onChange = viewModel::updateNotifications,
            onBack = { showNotifications = false },
        )
        current is SignInUiState.SignedIn && showSettings -> SettingsScreen(
            displayName = current.displayName,
            email = current.email,
            defaultWardCode = current.defaultWardCode,
            onWardSelected = viewModel::updateDefaultWard,
            onOpenNotifications = { showNotifications = true },
            onSignOut = {
                showSettings = false
                showNotifications = false
                viewModel.signOut()
            },
            onBack = { showSettings = false },
        )
        current is SignInUiState.SignedIn -> HomeScreen(
            displayName = current.displayName,
            email = current.email,
            sessionNote = current.sessionNote,
            onOpenSettings = { showSettings = true },
        )
        !onboardingDone -> OnboardingScreen(onContinue = viewModel::finishOnboarding)
        else -> LoginScreen(
            state = current,
            onGoogleSignIn = viewModel::signIn,
        )
    }
}

private const val SPLASH_MILLIS = 700L
private const val TAG = "MuniPulse"
