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
import za.co.munipulse.ui.home.HomeTab
import za.co.munipulse.ui.incidents.IncidentDetailScreen
import za.co.munipulse.ui.incidents.WardHotspotsScreen
import za.co.munipulse.ui.report.CreateIncidentScreen
import za.co.munipulse.ui.login.LoginScreen
import za.co.munipulse.ui.onboarding.OnboardingScreen
import za.co.munipulse.ui.settings.NotificationPreferencesScreen
import za.co.munipulse.ui.settings.ProfileScreen
import za.co.munipulse.ui.settings.SettingsScreen
import za.co.munipulse.ui.splash.SplashScreen

@Composable
fun MuniPulseApp(viewModel: GoogleSignInViewModel = viewModel()) {
    var splashHold by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var homeTab by remember { mutableStateOf(HomeTab.Pulse) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showHotspots by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val restoring by viewModel.restoring.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val onboardingDone by viewModel.onboardingComplete.collectAsState()
    val wardPulse by viewModel.wardPulse.collectAsState()
    val mine by viewModel.mine.collectAsState()
    val mineStatus by viewModel.mineStatus.collectAsState()
    val detail by viewModel.detail.collectAsState()
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
        current is SignInUiState.SignedIn && showReport -> CreateIncidentScreen(
            wardCode = current.defaultWardCode,
            onSubmit = viewModel::submitIncident,
            onBack = { showReport = false },
        )
        current is SignInUiState.SignedIn && showProfile -> ProfileScreen(
            displayName = current.displayName,
            email = current.email,
            defaultWardCode = current.defaultWardCode,
            preferredLanguage = current.preferredLanguage,
            role = current.role,
            onBack = { showProfile = false },
        )
        current is SignInUiState.SignedIn && showNotifications -> NotificationPreferencesScreen(
            preferences = notifications,
            onChange = viewModel::updateNotifications,
            onBack = { showNotifications = false },
        )
        current is SignInUiState.SignedIn && showSettings -> SettingsScreen(
            displayName = current.displayName,
            email = current.email,
            defaultWardCode = current.defaultWardCode,
            profileNote = current.profileNote,
            onOpenProfile = { showProfile = true },
            onWardSelected = viewModel::updateDefaultWard,
            onOpenNotifications = { showNotifications = true },
            onSignOut = {
                showSettings = false
                showNotifications = false
                showProfile = false
                showReport = false
                homeTab = HomeTab.Pulse
                detailId = null
                showHotspots = false
                viewModel.signOut()
            },
            onBack = { showSettings = false },
        )
        current is SignInUiState.SignedIn && detailId != null -> IncidentDetailScreen(
            incidentId = detailId.orEmpty(),
            detail = detail,
            onLoad = viewModel::loadIncident,
            onLoadPhoto = viewModel::loadPhotoJpeg,
            onBack = {
                detailId = null
                viewModel.clearIncident()
            },
        )
        current is SignInUiState.SignedIn && showHotspots -> WardHotspotsScreen(
            wardCode = current.defaultWardCode,
            pulse = wardPulse,
            onRefresh = viewModel::refreshWardPulse,
            onOpenIncident = { detailId = it },
            onOpenReport = { showReport = true },
            onBack = { showHotspots = false },
        )
        current is SignInUiState.SignedIn -> HomeScreen(
            wardCode = current.defaultWardCode,
            pulse = wardPulse,
            onRefresh = viewModel::refreshWardPulse,
            onWardSelected = viewModel::updateDefaultWard,
            tab = homeTab,
            onTab = { homeTab = it },
            mine = mine,
            mineStatus = mineStatus,
            onRefreshMine = viewModel::refreshMine,
            onMineStatus = viewModel::setMineStatus,
            onOpenIncident = { detailId = it },
            onOpenHotspots = { showHotspots = true },
            onOpenReport = { showReport = true },
            onOpenNotifications = { showNotifications = true },
            onOpenProfile = { showProfile = true },
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
