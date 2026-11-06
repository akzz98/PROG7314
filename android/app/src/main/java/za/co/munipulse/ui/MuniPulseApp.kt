package za.co.munipulse.ui

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import za.co.munipulse.PushLaunch
import za.co.munipulse.auth.GoogleSignInViewModel
import za.co.munipulse.auth.PushNotifications
import za.co.munipulse.auth.SignInUiState
import za.co.munipulse.ui.home.HomeScreen
import za.co.munipulse.ui.home.HomeTab
import za.co.munipulse.ui.incidents.IncidentDetailScreen
import za.co.munipulse.ui.incidents.WardHotspotsScreen
import za.co.munipulse.ui.report.CreateIncidentScreen
import za.co.munipulse.ui.login.LoginScreen
import za.co.munipulse.ui.onboarding.OnboardingScreen
import za.co.munipulse.ui.settings.LanguageScreen
import za.co.munipulse.ui.settings.NotificationPreferencesScreen
import za.co.munipulse.ui.settings.ProfileScreen
import za.co.munipulse.ui.settings.SettingsScreen
import za.co.munipulse.ui.settings.SyncCentreScreen
import za.co.munipulse.ui.login.BiometricGateScreen
import za.co.munipulse.ui.splash.SplashScreen

@Composable
fun MuniPulseApp(
    pushLaunch: PushLaunch? = null,
    onPushConsumed: () -> Unit = {},
    viewModel: GoogleSignInViewModel = viewModel(),
) {
    var splashHold by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSync by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var homeTabName by rememberSaveable { mutableStateOf(HomeTab.Pulse.name) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showHotspots by rememberSaveable { mutableStateOf(false) }
    val homeTab = runCatching { HomeTab.valueOf(homeTabName) }.getOrDefault(HomeTab.Pulse)
    val activity = LocalContext.current as Activity
    val appliedLanguage by viewModel.appliedLanguage.collectAsState()
    val state by viewModel.state.collectAsState()
    val restoring by viewModel.restoring.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val onboardingDone by viewModel.onboardingComplete.collectAsState()
    val wardPulse by viewModel.wardPulse.collectAsState()
    val mine by viewModel.mine.collectAsState()
    val mineStatus by viewModel.mineStatus.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val detailRefreshing by viewModel.detailRefreshing.collectAsState()
    val detailRefreshNote by viewModel.detailRefreshNote.collectAsState()
    val upvoteBusy by viewModel.upvoteBusy.collectAsState()
    val hotspots by viewModel.hotspots.collectAsState()
    val emergencyMap by viewModel.emergencyMap.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val biometricUnlocked by viewModel.biometricUnlocked.collectAsState()
    val gateMessage by viewModel.gateMessage.collectAsState()
    val gateWorking by viewModel.gateWorking.collectAsState()
    val syncCentre by viewModel.syncCentre.collectAsState()
    val showSplash = splashHold || state is SignInUiState.Checking || restoring

    LaunchedEffect(Unit) {
        if (viewModel.state.value is SignInUiState.Checking) {
            delay(SPLASH_MILLIS)
            viewModel.refresh()
        }
        splashHold = false
    }

    var localeRestart by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(appliedLanguage) {
        if (localeRestart == appliedLanguage) {
            return@LaunchedEffect
        }
        val locales = activity.resources.configuration.locales
        val active = if (locales.isEmpty) null else locales[0].language
        if (active != null && active != appliedLanguage) {
            localeRestart = appliedLanguage
            activity.recreate()
        }
    }

    LaunchedEffect(showSplash) {
        if (!showSplash) {
            val route = when {
                state is SignInUiState.SignedIn && biometricEnabled && !biometricUnlocked && !showReport -> "biometric"
                state is SignInUiState.SignedIn -> "home"
                !onboardingDone -> "onboarding"
                else -> "login"
            }
            Log.i(TAG, "Splash routed to $route")
        }
    }

    LaunchedEffect(pushLaunch, state, showSplash, biometricEnabled, biometricUnlocked) {
        val launch = pushLaunch ?: return@LaunchedEffect
        if (showSplash || state !is SignInUiState.SignedIn) {
            return@LaunchedEffect
        }
        if (biometricEnabled && !biometricUnlocked) {
            return@LaunchedEffect
        }
        showSettings = false
        showSync = false
        showLanguage = false
        showNotifications = false
        showProfile = false
        showReport = false
        showHotspots = false
        when (launch.type) {
            PushNotifications.TYPE_TICKET -> {
                val id = launch.incidentId
                if (!id.isNullOrBlank()) {
                    homeTabName = HomeTab.Mine.name
                    detailId = id
                    Log.i(TAG, "Push opened incident")
                }
            }
            PushNotifications.TYPE_AREA -> {
                val ward = launch.wardCode
                if (!ward.isNullOrBlank()) {
                    viewModel.updateDefaultWard(ward)
                    homeTabName = HomeTab.Pulse.name
                    detailId = null
                    viewModel.refreshWardPulse(ward)
                    Log.i(TAG, "Push opened ward home")
                }
            }
        }
        onPushConsumed()
    }

    val current = state
    when {
        showSplash -> SplashScreen()
        current is SignInUiState.SignedIn && showReport -> CreateIncidentScreen(
            wardCode = current.defaultWardCode,
            nearby = nearby,
            onLookupNearby = viewModel::lookupNearby,
            onClearNearby = viewModel::clearNearby,
            onSubmit = viewModel::submitIncident,
            onBack = {
                viewModel.clearNearby()
                showReport = false
            },
        )
        current is SignInUiState.SignedIn && !showReport && biometricEnabled && !biometricUnlocked -> BiometricGateScreen(
            working = gateWorking,
            message = gateMessage,
            onUnlocked = viewModel::unlockWithBiometric,
            onUseGoogle = { viewModel.confirmWithGoogle(activity) },
            onMessage = viewModel::setGateMessage,
        )
        current is SignInUiState.SignedIn && showProfile -> ProfileScreen(
            displayName = current.displayName,
            email = current.email,
            defaultWardCode = current.defaultWardCode,
            preferredLanguage = current.preferredLanguage,
            role = current.role,
            onBack = { showProfile = false },
        )
        current is SignInUiState.SignedIn && showLanguage -> LanguageScreen(
            selectedCode = current.preferredLanguage,
            onSave = { code ->
                viewModel.updatePreferredLanguage(code)
                showLanguage = false
            },
            onBack = { showLanguage = false },
        )
        current is SignInUiState.SignedIn && showNotifications -> NotificationPreferencesScreen(
            preferences = notifications,
            onChange = viewModel::updateNotifications,
            onBack = { showNotifications = false },
        )
        current is SignInUiState.SignedIn && showSync -> SyncCentreScreen(
            centre = syncCentre,
            onRefresh = viewModel::refreshSyncCentre,
            onRetry = viewModel::retryQueued,
            onSyncNow = viewModel::syncQueuedNow,
            onBack = { showSync = false },
        )
        current is SignInUiState.SignedIn && showSettings -> SettingsScreen(
            displayName = current.displayName,
            email = current.email,
            defaultWardCode = current.defaultWardCode,
            profileNote = current.profileNote,
            preferredLanguage = current.preferredLanguage,
            onOpenProfile = { showProfile = true },
            onWardSelected = viewModel::updateDefaultWard,
            onOpenLanguage = { showLanguage = true },
            onOpenNotifications = { showNotifications = true },
            biometricEnabled = biometricEnabled,
            onBiometricChange = viewModel::setBiometricEnabled,
            onOpenSync = { showSync = true },
            onSignOut = {
                showSettings = false
                showSync = false
                showLanguage = false
                showNotifications = false
                showProfile = false
                showReport = false
                homeTabName = HomeTab.Pulse.name
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
            upvoteBusy = upvoteBusy,
            onUpvote = viewModel::upvoteIncident,
            refreshing = detailRefreshing,
            refreshNote = detailRefreshNote,
            onRefresh = viewModel::refreshIncident,
            onBack = {
                detailId = null
                viewModel.clearIncident()
            },
        )
        current is SignInUiState.SignedIn && showHotspots -> WardHotspotsScreen(
            wardCode = current.defaultWardCode,
            hotspots = hotspots,
            onRefresh = viewModel::refreshHotspots,
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
            onTab = { homeTabName = it.name },
            mine = mine,
            mineStatus = mineStatus,
            onRefreshMine = viewModel::refreshMine,
            onMineStatus = viewModel::setMineStatus,
            onOpenIncident = { detailId = it },
            onOpenHotspots = { showHotspots = true },
            onOpenReport = { showReport = true },
            emergencyMap = emergencyMap,
            onRefreshMap = { viewModel.refreshEmergencyMap(current.defaultWardCode) },
            onNavigateCached = { viewModel.showCachedEmergencyMap(current.defaultWardCode) },
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
