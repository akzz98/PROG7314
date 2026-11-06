package za.co.munipulse.auth

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import za.co.munipulse.R
import za.co.munipulse.data.CachedMapPoint
import za.co.munipulse.data.DeviceNetwork
import za.co.munipulse.data.LocalSettingsMirror
import za.co.munipulse.data.MuniPulseDatabase
import za.co.munipulse.data.PendingSyncOperation
import za.co.munipulse.data.QueuedIncident
import za.co.munipulse.data.QueuedPhotos
import za.co.munipulse.data.SyncAttempt
import za.co.munipulse.data.SyncStatus
import za.co.munipulse.report.IncidentDraft
import za.co.munipulse.report.IncidentSubmitResult
import za.co.munipulse.report.IncidentValidator
import za.co.munipulse.report.PhotoFiles
import za.co.munipulse.ui.report.NearbyDuplicate
import za.co.munipulse.ui.report.NearbyUi
import za.co.munipulse.ui.home.WardPulseItem
import za.co.munipulse.ui.home.WardPulseUi
import za.co.munipulse.ui.incidents.HotspotGroup
import za.co.munipulse.ui.incidents.IncidentDetailItem
import za.co.munipulse.ui.incidents.IncidentDetailUi
import za.co.munipulse.ui.incidents.IncidentPhoto
import za.co.munipulse.ui.incidents.MyIncidentItem
import za.co.munipulse.ui.incidents.MyIncidentsUi
import za.co.munipulse.ui.incidents.IncidentTime
import za.co.munipulse.ui.incidents.TimelineLine
import za.co.munipulse.ui.incidents.WardHotspotsUi
import za.co.munipulse.ui.map.EmergencyMapUi
import za.co.munipulse.ui.map.EmergencyPointItem
import java.io.File

sealed interface SignInUiState {
    data object Checking : SignInUiState
    data object Ready : SignInUiState
    data object Working : SignInUiState
    data class Failed(val message: String) : SignInUiState
    data class SignedIn(
        val displayName: String,
        val email: String,
        val sessionNote: String,
        val defaultWardCode: String,
        val profileNote: String = "",
        val preferredLanguage: String = "en",
        val role: String = "Citizen",
    ) : SignInUiState
    data object MissingConfig : SignInUiState
    data object MissingWebClient : SignInUiState
}

internal const val FALLBACK_DISPLAY_NAME = "Google account"

class GoogleSignInViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Checking)
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(application)
    private val appLanguage = AppLanguage(application)
    private val biometricStore = BiometricStore(application)
    private val settingsMirror = LocalSettingsMirror(application)
    private val onboardingStore = OnboardingStore(application)
    private val notificationStore = NotificationStore(application)
    private val pushTokenStore = PushTokenStore(application)
    private val _appliedLanguage = MutableStateFlow(appLanguage.read())
    val appliedLanguage: StateFlow<String> = _appliedLanguage.asStateFlow()
    private val _biometricEnabled = MutableStateFlow(biometricStore.read())
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()
    private val _biometricUnlocked = MutableStateFlow(false)
    val biometricUnlocked: StateFlow<Boolean> = _biometricUnlocked.asStateFlow()
    private val _gateMessage = MutableStateFlow("")
    val gateMessage: StateFlow<String> = _gateMessage.asStateFlow()
    private val _gateWorking = MutableStateFlow(false)
    val gateWorking: StateFlow<Boolean> = _gateWorking.asStateFlow()
    private val _syncCentre = MutableStateFlow<SyncCentreUi>(SyncCentreUi.Loading)
    val syncCentre: StateFlow<SyncCentreUi> = _syncCentre.asStateFlow()
    private var accessToken: String? = null
    private val profileMutex = Mutex()

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(onboardingStore.isComplete())
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    private val _notifications = MutableStateFlow(notificationStore.read())
    val notifications: StateFlow<NotificationPreferences> = _notifications.asStateFlow()

    private val _wardPulse = MutableStateFlow<WardPulseUi>(WardPulseUi.Idle)
    val wardPulse: StateFlow<WardPulseUi> = _wardPulse.asStateFlow()
    private var pulseGeneration = 0

    private val _mineStatus = MutableStateFlow<String?>(null)
    val mineStatus: StateFlow<String?> = _mineStatus.asStateFlow()
    private val _mine = MutableStateFlow<MyIncidentsUi>(MyIncidentsUi.Idle)
    val mine: StateFlow<MyIncidentsUi> = _mine.asStateFlow()
    private var mineGeneration = 0

    private val _detail = MutableStateFlow<IncidentDetailUi>(IncidentDetailUi.Idle)
    val detail: StateFlow<IncidentDetailUi> = _detail.asStateFlow()
    private val _detailRefreshing = MutableStateFlow(false)
    val detailRefreshing: StateFlow<Boolean> = _detailRefreshing.asStateFlow()
    private val _detailRefreshNote = MutableStateFlow("")
    val detailRefreshNote: StateFlow<String> = _detailRefreshNote.asStateFlow()
    private var detailGeneration = 0
    private val _nearby = MutableStateFlow<NearbyUi>(NearbyUi.Idle)
    val nearby: StateFlow<NearbyUi> = _nearby.asStateFlow()
    private var nearbyGeneration = 0
    private val _hotspots = MutableStateFlow<WardHotspotsUi>(WardHotspotsUi.Idle)
    val hotspots: StateFlow<WardHotspotsUi> = _hotspots.asStateFlow()
    private var hotspotGeneration = 0
    private val _emergencyMap = MutableStateFlow<EmergencyMapUi>(EmergencyMapUi.Idle)
    val emergencyMap: StateFlow<EmergencyMapUi> = _emergencyMap.asStateFlow()
    private var emergencyGeneration = 0
    private val _upvoteBusy = MutableStateFlow(false)
    val upvoteBusy: StateFlow<Boolean> = _upvoteBusy.asStateFlow()
    private var upvoteInFlight = false

    init {
        mirrorSettings()
    }

    fun updateNotifications(preferences: NotificationPreferences) {
        notificationStore.save(preferences)
        notificationStore.setPendingSync(true)
        _notifications.value = preferences
        requestProfileSync(pushLocal = true)
    }

    fun finishOnboarding() {
        if (_onboardingComplete.value) {
            return
        }
        onboardingStore.markComplete()
        _onboardingComplete.value = true
        Log.i(TAG, "Onboarding finished; login is next")
    }

    fun refresh() {
        val context = getApplication<Application>()
        if (!GoogleSignIn.isConfigured(context)) {
            Log.i(TAG, "Firebase config is missing; Google sign-in stays disabled")
            _restoring.value = false
            _state.value = SignInUiState.MissingConfig
            return
        }
        if (!GoogleSignIn.hasWebClient(context)) {
            Log.w(TAG, "Firebase is configured but the Google Web client id is missing")
            _restoring.value = false
            _state.value = SignInUiState.MissingWebClient
            return
        }
        val user = GoogleSignIn.currentUser(context)
        if (user == null) {
            sessionStore.clear()
            accessToken = null
            _restoring.value = false
            _state.value = SignInUiState.Ready
            return
        }
        Log.i(TAG, "Existing Firebase user uid ${user.uid}")
        val stored = sessionStore.read()
        if (stored != null && !stored.isExpired()) {
            accessToken = stored.accessToken
            _restoring.value = false
            Log.i(TAG, "Restored API session for user ${stored.userId}")
            _state.value = signedIn(
                stored.displayName.ifBlank { user.displayName },
                stored.email.ifBlank { user.email },
                context.getString(R.string.session_ready),
                stored.defaultWardCode,
                preferredLanguage = persistLanguage(sessionStore.readLanguage() ?: appLanguage.read()),
            )
            requestProfileSync(pushLocal = false)
            registerPushToken(stored.accessToken)
            return
        }
        _restoring.value = true
        exchangeSession(user)
    }

    fun signIn(activity: Activity) {
        if (_state.value is SignInUiState.Working) {
            return
        }
        _state.value = SignInUiState.Working
        viewModelScope.launch {
            val next = try {
                val user = GoogleSignIn.signIn(activity)
                sessionState(user)
            } catch (cancelled: GetCredentialCancellationException) {
                SignInUiState.Failed(activity.getString(R.string.login_cancelled))
            } catch (missing: MissingFirebaseConfigException) {
                Log.w(TAG, "Google sign-in blocked: Firebase config is missing")
                SignInUiState.MissingConfig
            } catch (missingWebClient: MissingWebClientException) {
                Log.w(TAG, "Google sign-in blocked: Web client id is missing")
                SignInUiState.MissingWebClient
            } catch (error: Exception) {
                Log.e(TAG, "Google sign-in failed: ${error.javaClass.simpleName}")
                SignInUiState.Failed(activity.getString(R.string.login_failed))
            }
            _state.value = next
            if (next is SignInUiState.SignedIn) {
                requestProfileSync(pushLocal = false)
            }
        }
    }

    fun updatePreferredLanguage(code: String): Boolean {
        val current = _state.value as? SignInUiState.SignedIn ?: return false
        val next = LanguageCodes.normalize(code)
        if (current.preferredLanguage == next && appLanguage.read() == next) {
            return false
        }
        persistLanguage(next)
        _state.value = current.copy(preferredLanguage = next, profileNote = "")
        notificationStore.setPendingSync(true)
        requestProfileSync(pushLocal = true)
        return true
    }

    fun setBiometricEnabled(enabled: Boolean): Int? {
        if (!enabled) {
            biometricStore.save(false)
            _biometricEnabled.value = false
            mirrorSettings()
            Log.i(TAG, "Biometric unlock off")
            return null
        }
        return when (BiometricUnlock.availability(getApplication())) {
            BiometricAvailability.NoneEnrolled -> {
                Log.i(TAG, "Biometric unlock refused. None enrolled")
                R.string.biometric_settings_none
            }
            BiometricAvailability.Unavailable -> {
                Log.i(TAG, "Biometric unlock refused. Unavailable")
                R.string.biometric_settings_unavailable
            }
            BiometricAvailability.Ready -> {
                biometricStore.save(true)
                _biometricEnabled.value = true
                _biometricUnlocked.value = true
                mirrorSettings()
                Log.i(TAG, "Biometric unlock on")
                null
            }
        }
    }

    fun unlockWithBiometric() {
        _gateMessage.value = ""
        _biometricUnlocked.value = true
        Log.i(TAG, "Biometric gate unlocked")
    }

    fun setGateMessage(message: String) {
        _gateMessage.value = message
    }

    fun confirmWithGoogle(activity: Activity) {
        if (_gateWorking.value) {
            return
        }
        viewModelScope.launch {
            _gateWorking.value = true
            _gateMessage.value = ""
            try {
                val previous = GoogleSignIn.currentUser(getApplication())?.uid
                val user = GoogleSignIn.signIn(activity)
                if (previous == null || user.uid != previous) {
                    Log.i(TAG, "Google unlock opened a session")
                    _state.value = sessionState(user)
                } else {
                    Log.i(TAG, "Google unlock matched the signed-in account")
                }
                _biometricUnlocked.value = true
            } catch (cancelled: GetCredentialCancellationException) {
                Log.i(TAG, "Google unlock cancelled")
                _gateMessage.value = activity.getString(R.string.biometric_cancelled)
            } catch (error: Exception) {
                Log.e(TAG, "Google unlock failed: ${error.javaClass.simpleName}")
                _gateMessage.value = activity.getString(R.string.biometric_google_failed)
            } finally {
                _gateWorking.value = false
            }
        }
    }

    fun updateDefaultWard(code: String) {
        val current = _state.value as? SignInUiState.SignedIn ?: return
        if (!sessionStore.updateWard(code)) {
            Log.w(TAG, "Ignored unknown ward selection")
            return
        }
        _state.value = current.copy(defaultWardCode = code)
        notificationStore.setPendingSync(true)
        Log.i(TAG, "Default ward set to $code")
        requestProfileSync(pushLocal = true)
    }

    fun refreshWardPulse(wardCode: String) {
        val generation = ++pulseGeneration
        val ward = WardCatalog.normalize(wardCode)
        viewModelScope.launch {
            _wardPulse.value = WardPulseUi.Loading
            val token = accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Ward pulse skipped. No API session")
                if (generation == pulseGeneration) {
                    _wardPulse.value = WardPulseUi.Failed(getApplication<Application>().getString(R.string.report_auth))
                }
                return@launch
            }
            try {
                val open = IncidentClient.listWard(token, ward)
                    .filter { it.status != "Resolved" && it.id.isNotBlank() }
                    .map { item ->
                        WardPulseItem(
                            id = item.id,
                            category = item.category,
                            place = placeLabel(item.description),
                            upvoteCount = item.upvoteCount,
                            viewerHasUpvoted = item.viewerHasUpvoted,
                        )
                    }
                if (generation != pulseGeneration) {
                    return@launch
                }
                Log.i(TAG, "Ward pulse loaded. Ward $ward. Open ${open.size}")
                _wardPulse.value = WardPulseUi.Ready(open.size, open)
            } catch (error: Exception) {
                if (generation != pulseGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Ward pulse failed: $reason")
                _wardPulse.value = WardPulseUi.Failed(getApplication<Application>().getString(R.string.pulse_failed))
            }
        }
    }

    fun setMineStatus(status: String?) {
        val next = when (status) {
            null, "Submitted", "InProgress", "Resolved" -> status
            else -> {
                Log.w(TAG, "Ignored unknown incident status filter")
                return
            }
        }
        if (_mineStatus.value == next) {
            return
        }
        _mineStatus.value = next
        refreshMine()
    }

    fun refreshMine() {
        val generation = ++mineGeneration
        val status = _mineStatus.value
        viewModelScope.launch {
            _mine.value = MyIncidentsUi.Loading
            val token = accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "My incidents skipped. No API session")
                if (generation == mineGeneration) {
                    _mine.value = MyIncidentsUi.Failed(getApplication<Application>().getString(R.string.report_auth))
                }
                return@launch
            }
            try {
                val items = IncidentClient.listMine(token, status)
                    .filter { it.id.isNotBlank() }
                    .map { item ->
                        MyIncidentItem(
                            id = item.id,
                            category = item.category,
                            status = item.status,
                            createdAt = item.createdAt,
                            upvoteCount = item.upvoteCount,
                            viewerHasUpvoted = item.viewerHasUpvoted,
                        )
                    }
                if (generation != mineGeneration) {
                    return@launch
                }
                Log.i(TAG, "My incidents loaded. Filter ${status ?: "All"}. Count ${items.size}")
                _mine.value = MyIncidentsUi.Ready(items)
            } catch (error: Exception) {
                if (generation != mineGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "My incidents failed: $reason")
                _mine.value = MyIncidentsUi.Failed(getApplication<Application>().getString(R.string.mine_failed))
            }
        }
    }

    fun loadIncident(incidentId: String) {
        fetchIncident(incidentId, refreshing = false)
    }

    fun refreshIncident(incidentId: String) {
        val current = _detail.value as? IncidentDetailUi.Ready ?: return
        if (current.item.id != incidentId.trim() || _detailRefreshing.value) {
            return
        }
        fetchIncident(incidentId, refreshing = true)
    }

    private fun fetchIncident(incidentId: String, refreshing: Boolean) {
        val generation = ++detailGeneration
        val id = incidentId.trim()
        val kept = if (refreshing) _detail.value as? IncidentDetailUi.Ready else null
        viewModelScope.launch {
            if (refreshing) {
                _detailRefreshing.value = true
                _detailRefreshNote.value = ""
            } else {
                _detail.value = IncidentDetailUi.Loading
            }
            val token = accessToken
            if (token.isNullOrBlank() || id.isEmpty()) {
                Log.w(TAG, "Incident detail skipped. No API session")
                if (generation == detailGeneration) {
                    if (kept != null) {
                        _detailRefreshNote.value = getApplication<Application>().getString(R.string.report_auth)
                    } else {
                        _detail.value = IncidentDetailUi.Failed(getApplication<Application>().getString(R.string.detail_failed))
                    }
                    _detailRefreshing.value = false
                }
                return@launch
            }
            try {
                val body = IncidentClient.detail(token, id)
                if (body.id.isBlank()) {
                    throw IncidentRequestException("EMPTY", "The incident response was empty.")
                }
                val item = IncidentDetailItem(
                    id = body.id,
                    category = body.category,
                    place = placeLabel(body.description),
                    description = body.description.trim(),
                    status = body.status,
                    latitude = body.latitude,
                    longitude = body.longitude,
                    upvoteCount = body.upvoteCount,
                    viewerHasUpvoted = body.viewerHasUpvoted,
                    grouped = !body.aggregateId.isNullOrBlank(),
                    photos = body.photos.orEmpty()
                        .filter { it.id.isNotBlank() && !it.url.isNullOrBlank() }
                        .map { IncidentPhoto(it.id, it.url!!) },
                    timeline = body.timeline.orEmpty()
                        .map { event ->
                            TimelineLine(
                                at = event.at,
                                type = event.type,
                                note = event.note.trim(),
                                actorRole = event.actorRole,
                            )
                        }
                        .sortedBy { IncidentTime.epochMillis(it.at) },
                )
                if (generation != detailGeneration) {
                    return@launch
                }
                val action = if (refreshing) "refreshed" else "loaded"
                Log.i(TAG, "Incident detail $action ${item.id}. Timeline ${item.timeline.size}")
                _detail.value = IncidentDetailUi.Ready(item)
                _detailRefreshNote.value = ""
            } catch (error: Exception) {
                if (generation != detailGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Incident detail failed: $reason")
                if (kept != null) {
                    _detailRefreshNote.value = getApplication<Application>().getString(R.string.detail_refresh_failed)
                } else {
                    _detail.value = IncidentDetailUi.Failed(getApplication<Application>().getString(R.string.detail_failed))
                }
            } finally {
                if (generation == detailGeneration) {
                    _detailRefreshing.value = false
                }
            }
        }
    }

    fun lookupNearby(category: String, latitude: Double, longitude: Double, wardCode: String) {
        val generation = ++nearbyGeneration
        val ward = WardCatalog.normalize(wardCode)
        viewModelScope.launch {
            _nearby.value = NearbyUi.Loading
            val token = accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Nearby lookup skipped. No API session")
                if (generation == nearbyGeneration) {
                    _nearby.value = NearbyUi.Failed(getApplication<Application>().getString(R.string.report_auth))
                }
                return@launch
            }
            try {
                val items = IncidentClient.listNearby(token, category, latitude, longitude, ward)
                    .filter { it.id.isNotBlank() }
                    .map { item ->
                        NearbyDuplicate(
                            id = item.id,
                            category = item.category,
                            place = item.place,
                            upvoteCount = item.upvoteCount,
                            distanceMeters = item.distanceMeters,
                        )
                    }
                if (generation != nearbyGeneration) {
                    return@launch
                }
                Log.i(TAG, "Nearby duplicates loaded. Count ${items.size}")
                _nearby.value = NearbyUi.Ready(items)
            } catch (error: Exception) {
                if (generation != nearbyGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Nearby lookup failed: $reason")
                _nearby.value = NearbyUi.Failed(getApplication<Application>().getString(R.string.nearby_failed))
            }
        }
    }

    fun clearNearby() {
        nearbyGeneration++
        _nearby.value = NearbyUi.Idle
    }

    fun refreshHotspots(wardCode: String) {
        val generation = ++hotspotGeneration
        val ward = WardCatalog.normalize(wardCode)
        viewModelScope.launch {
            _hotspots.value = WardHotspotsUi.Loading
            val token = accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Ward hotspots skipped. No API session")
                if (generation == hotspotGeneration) {
                    _hotspots.value = WardHotspotsUi.Failed(getApplication<Application>().getString(R.string.report_auth))
                }
                return@launch
            }
            try {
                val groups = IncidentClient.listAggregates(token, ward)
                    .filter { it.aggregateId.isNotBlank() && it.incidentId.isNotBlank() }
                    .mapIndexed { index, group ->
                        HotspotGroup(
                            aggregateId = group.aggregateId,
                            category = group.category,
                            reportCount = group.reportCount,
                            upvoteCount = group.upvoteCount,
                            incidentId = group.incidentId,
                            rank = index + 1,
                        )
                    }
                if (generation != hotspotGeneration) {
                    return@launch
                }
                Log.i(TAG, "Ward hotspots loaded. Ward $ward. Groups ${groups.size}")
                _hotspots.value = WardHotspotsUi.Ready(groups)
            } catch (error: Exception) {
                if (generation != hotspotGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Ward hotspots failed: $reason")
                _hotspots.value = WardHotspotsUi.Failed(getApplication<Application>().getString(R.string.hotspots_failed))
            }
        }
    }

    fun refreshEmergencyMap(wardCode: String) {
        val generation = ++emergencyGeneration
        val ward = WardCatalog.normalize(wardCode)
        viewModelScope.launch {
            _emergencyMap.value = EmergencyMapUi.Loading
            val cached = cachedEmergency(ward)
            if (!DeviceNetwork.isOnline(getApplication())) {
                Log.i(TAG, "Emergency map offline. Ward $ward. Cached ${cached.size}")
                if (generation == emergencyGeneration) {
                    _emergencyMap.value = EmergencyMapUi.Failed(
                        getApplication<Application>().getString(R.string.map_offline),
                        cacheAvailable = cached.isNotEmpty(),
                    )
                }
                return@launch
            }
            val token = accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Emergency map skipped. No API session")
                if (generation == emergencyGeneration) {
                    _emergencyMap.value = EmergencyMapUi.Failed(
                        getApplication<Application>().getString(R.string.report_auth),
                        cacheAvailable = cached.isNotEmpty(),
                    )
                }
                return@launch
            }
            try {
                val points = IncidentClient.emergencyPoints(token, ward)
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .map { body ->
                        EmergencyPointItem(
                            id = body.id,
                            kind = body.kind,
                            name = body.name,
                            latitude = body.latitude,
                            longitude = body.longitude,
                        )
                    }
                if (generation != emergencyGeneration) {
                    return@launch
                }
                if (points.isEmpty()) {
                    Log.i(TAG, "Emergency map empty. Ward $ward")
                    _emergencyMap.value = EmergencyMapUi.Empty
                    return@launch
                }
                val fetchedAt = System.currentTimeMillis()
                MuniPulseDatabase.get(getApplication()).cachedMapPoints().replaceWard(
                    ward,
                    points.map { point ->
                        CachedMapPoint(
                            id = point.id,
                            wardCode = ward,
                            kind = point.kind,
                            name = point.name,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            fetchedAt = fetchedAt,
                        )
                    },
                )
                Log.i(TAG, "Emergency map loaded. Ward $ward. Points ${points.size}")
                _emergencyMap.value = EmergencyMapUi.Ready(points, fetchedAt, fromCache = false)
            } catch (error: Exception) {
                if (generation != emergencyGeneration) {
                    return@launch
                }
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Emergency map failed: $reason")
                _emergencyMap.value = EmergencyMapUi.Failed(
                    getApplication<Application>().getString(R.string.map_failed),
                    cacheAvailable = cached.isNotEmpty(),
                )
            }
        }
    }

    fun showCachedEmergencyMap(wardCode: String) {
        val generation = ++emergencyGeneration
        val ward = WardCatalog.normalize(wardCode)
        viewModelScope.launch {
            val cached = cachedEmergency(ward)
            if (generation != emergencyGeneration) {
                return@launch
            }
            if (cached.isEmpty()) {
                _emergencyMap.value = EmergencyMapUi.Empty
                return@launch
            }
            val fetchedAt = cached.maxOf { it.fetchedAt }
            Log.i(TAG, "Emergency map opened from cache. Ward $ward. Points ${cached.size}")
            _emergencyMap.value = EmergencyMapUi.Ready(
                points = cached.map { row ->
                    EmergencyPointItem(
                        id = row.id,
                        kind = row.kind,
                        name = row.name,
                        latitude = row.latitude,
                        longitude = row.longitude,
                    )
                },
                fetchedAt = fetchedAt,
                fromCache = true,
            )
        }
    }

    private suspend fun cachedEmergency(wardCode: String): List<CachedMapPoint> =
        MuniPulseDatabase.get(getApplication()).cachedMapPoints().list(wardCode)

    fun clearIncident() {
        detailGeneration++
        upvoteInFlight = false
        _upvoteBusy.value = false
        _detailRefreshing.value = false
        _detailRefreshNote.value = ""
        _detail.value = IncidentDetailUi.Idle
    }

    fun upvoteIncident(incidentId: String) {
        val ready = _detail.value as? IncidentDetailUi.Ready ?: return
        if (ready.item.id != incidentId || upvoteInFlight) {
            return
        }
        if (ready.item.viewerHasUpvoted) {
            Log.i(TAG, "Upvote already recorded $incidentId")
            return
        }
        upvoteInFlight = true
        _upvoteBusy.value = true
        viewModelScope.launch {
            val token = accessToken
            try {
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "Upvote skipped. No API session")
                    applyUpvote(incidentId, ready.item.upvoteCount, voted = false, note = R.string.report_auth)
                    return@launch
                }
                when (val outcome = IncidentClient.upvote(token, incidentId)) {
                    is UpvoteOutcome.Counted -> {
                        Log.i(TAG, "Upvote on $incidentId count ${outcome.upvoteCount}")
                        applyUpvote(incidentId, outcome.upvoteCount, voted = true, note = 0)
                    }
                    UpvoteOutcome.Already -> {
                        Log.i(TAG, "Upvote duplicate $incidentId")
                        applyUpvote(incidentId, ready.item.upvoteCount, voted = true, note = R.string.upvote_already)
                    }
                }
            } catch (error: Exception) {
                val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
                Log.e(TAG, "Upvote failed: $reason")
                applyUpvote(incidentId, ready.item.upvoteCount, voted = false, note = R.string.upvote_failed)
            } finally {
                upvoteInFlight = false
                _upvoteBusy.value = false
            }
        }
    }

    private fun applyUpvote(incidentId: String, upvoteCount: Int, voted: Boolean, note: Int) {
        val ready = _detail.value as? IncidentDetailUi.Ready ?: return
        if (ready.item.id != incidentId) {
            return
        }
        val message = if (note == 0) "" else getApplication<Application>().getString(note)
        _detail.value = IncidentDetailUi.Ready(
            ready.item.copy(
                upvoteCount = upvoteCount,
                viewerHasUpvoted = voted,
                upvoteNote = message,
            ),
        )
        val pulse = _wardPulse.value
        if (pulse is WardPulseUi.Ready) {
            _wardPulse.value = pulse.copy(
                items = pulse.items.map { item ->
                    if (item.id == incidentId) {
                        item.copy(upvoteCount = upvoteCount, viewerHasUpvoted = voted)
                    } else {
                        item
                    }
                },
            )
        }
        val mine = _mine.value
        if (mine is MyIncidentsUi.Ready) {
            _mine.value = mine.copy(
                items = mine.items.map { item ->
                    if (item.id == incidentId) {
                        item.copy(upvoteCount = upvoteCount, viewerHasUpvoted = voted)
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun refreshSyncCentre() {
        viewModelScope.launch {
            publishSyncCentre(note = currentSyncNote(), busy = false)
        }
    }

    fun retryQueued(id: String) {
        viewModelScope.launch {
            publishSyncCentre(note = currentSyncNote(), busy = true)
            try {
                val database = MuniPulseDatabase.get(getApplication())
                val row = database.pendingSync().list().firstOrNull { it.id == id }
                if (row == null || row.status != SyncStatus.PENDING) {
                    publishSyncCentre(note = currentSyncNote(), busy = false)
                    return@launch
                }
                if (!DeviceNetwork.isOnline(getApplication())) {
                    database.pendingSync().upsert(SyncAttempt.retry(row, online = false))
                    Log.i(TAG, "Queued retry ${row.id}. Count ${row.retryCount + 1}")
                    publishSyncCentre(note = syncNote(online = false), busy = false)
                    return@launch
                }
                pushRows(listOf(row))
            } catch (error: Exception) {
                Log.e(TAG, "Sync centre retry failed: ${error.javaClass.simpleName}")
                publishSyncCentre(note = syncFailedNote(), busy = false)
            }
        }
    }

    fun syncQueuedNow() {
        viewModelScope.launch {
            publishSyncCentre(note = currentSyncNote(), busy = true)
            try {
                val online = DeviceNetwork.isOnline(getApplication())
                val database = MuniPulseDatabase.get(getApplication())
                val pending = database.pendingSync().list().filter { it.status == SyncStatus.PENDING }
                if (!online) {
                    pending.forEach { row ->
                        database.pendingSync().upsert(SyncAttempt.retry(row, online = false))
                    }
                    Log.i(TAG, "Sync now recorded ${pending.size} queued reports. Online false")
                    publishSyncCentre(note = syncNote(online = false), busy = false)
                    return@launch
                }
                if (pending.isEmpty()) {
                    publishSyncCentre(note = syncNote(online = true), busy = false)
                    return@launch
                }
                pushRows(pending)
            } catch (error: Exception) {
                Log.e(TAG, "Sync now failed: ${error.javaClass.simpleName}")
                publishSyncCentre(note = syncFailedNote(), busy = false)
            }
        }
    }

    private suspend fun pushRows(rows: List<PendingSyncOperation>) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Sync push skipped. No API session")
            publishSyncCentre(note = getApplication<Application>().getString(R.string.report_auth), busy = false)
            return
        }
        val database = MuniPulseDatabase.get(getApplication())
        val ready = ArrayList<Pair<PendingSyncOperation, List<String>>>()
        for (row in rows) {
            if (row.status != SyncStatus.PENDING) {
                continue
            }
            try {
                val paths = QueuedIncident.readPhotoPaths(row.photoLocalUri)
                val files = readQueuedPhotos(paths) ?: throw IncidentRequestException("UPLOAD_FAILED", "photo")
                val photoIds = if (files.isEmpty()) emptyList() else IncidentClient.uploadPhotos(token, files)
                ready += row to photoIds
            } catch (error: Exception) {
                val code = if (error is IncidentRequestException) error.code else "UPLOAD_FAILED"
                database.pendingSync().upsert(row.copy(retryCount = row.retryCount + 1, lastError = code))
                Log.e(TAG, "Queued photo upload failed: $code")
            }
        }
        if (ready.isEmpty()) {
            publishSyncCentre(note = getApplication<Application>().getString(R.string.sync_push_failed), busy = false)
            return
        }
        try {
            val operations = ready.map { (row, photoIds) ->
                val payload = QueuedIncident.readPayload(row.payloadJson)
                SyncOperationBody(
                    clientMutationId = row.id,
                    mutationType = row.mutationType,
                    category = payload.category,
                    description = payload.description,
                    latitude = payload.latitude,
                    longitude = payload.longitude,
                    accuracyMeters = payload.accuracyMeters,
                    wardCode = payload.wardCode,
                    photoIds = photoIds,
                )
            }
            val results = IncidentClient.push(token, SyncPushBody(operations))
            val byId = results.associateBy { it.clientMutationId }
            var ackCount = 0
            var conflictCount = 0
            for ((row, _) in ready) {
                val result = byId[row.id]
                val updated = if (result == null) {
                    row.copy(retryCount = row.retryCount + 1, lastError = "push")
                } else {
                    SyncAttempt.apply(row, result.result, result.code)
                }
                if (updated.status == SyncStatus.ACK) {
                    ackCount += 1
                }
                if (updated.status == SyncStatus.CONFLICT) {
                    conflictCount += 1
                }
                database.pendingSync().upsert(updated)
            }
            settingsMirror.markSynced(System.currentTimeMillis())
            Log.i(TAG, "Sync push finished. Ack $ackCount. Conflict $conflictCount")
            publishSyncCentre(
                note = getApplication<Application>().getString(R.string.sync_sent, ackCount, conflictCount),
                busy = false,
            )
        } catch (error: Exception) {
            val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
            Log.e(TAG, "Sync push failed: $reason")
            publishSyncCentre(note = getApplication<Application>().getString(R.string.sync_push_failed), busy = false)
        }
    }

    private suspend fun readQueuedPhotos(paths: List<String>): List<ByteArray>? = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) {
            return@withContext emptyList()
        }
        val files = ArrayList<ByteArray>(paths.size)
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) {
                return@withContext null
            }
            files += file.readBytes()
        }
        files
    }

    private suspend fun publishSyncCentre(note: String, busy: Boolean) {
        try {
            val database = MuniPulseDatabase.get(getApplication())
            val settings = database.localSettings().read()
            val items = database.pendingSync().list().map { row ->
                QueuedReportRow(
                    id = row.id,
                    category = queuedCategory(row.payloadJson),
                    status = row.status,
                    retryCount = row.retryCount,
                    errorCode = row.lastError,
                )
            }
            _syncCentre.value = SyncCentreUi.Ready(
                lastSyncAt = settings?.lastSyncAt,
                items = items,
                note = note,
                busy = busy,
            )
            Log.i(TAG, "Sync centre loaded ${items.size} queued reports")
        } catch (error: Exception) {
            Log.e(TAG, "Sync centre load failed: ${error.javaClass.simpleName}")
            _syncCentre.value = SyncCentreUi.Failed(syncFailedNote())
        }
    }

    private fun queuedCategory(payloadJson: String): String {
        return try {
            QueuedIncident.readPayload(payloadJson).category
        } catch (error: Exception) {
            Log.e(TAG, "Queued report could not be read: ${error.javaClass.simpleName}")
            ""
        }
    }

    private fun currentSyncNote(): String {
        val ready = _syncCentre.value as? SyncCentreUi.Ready
        return ready?.note.orEmpty()
    }

    private fun syncNote(online: Boolean): String {
        val resources = getApplication<Application>().resources
        return resources.getString(if (online) R.string.sync_waiting else R.string.sync_offline)
    }

    private fun syncFailedNote(): String =
        getApplication<Application>().getString(R.string.sync_failed)

    suspend fun loadPhotoJpeg(url: String): ByteArray? = IncidentClient.photoJpeg(url)

    fun signOut() {
        pulseGeneration++
        mineGeneration++
        detailGeneration++
        _detailRefreshing.value = false
        _detailRefreshNote.value = ""
        hotspotGeneration++
        nearbyGeneration++
        _nearby.value = NearbyUi.Idle
        upvoteInFlight = false
        _upvoteBusy.value = false
        _hotspots.value = WardHotspotsUi.Idle
        _wardPulse.value = WardPulseUi.Idle
        _mine.value = MyIncidentsUi.Idle
        _mineStatus.value = null
        _detail.value = IncidentDetailUi.Idle
        _biometricUnlocked.value = false
        _gateMessage.value = ""
        _gateWorking.value = false
        _syncCentre.value = SyncCentreUi.Loading
        val token = accessToken
        accessToken = null
        sessionStore.clear()
        notificationStore.setPendingSync(false)
        viewModelScope.launch {
            clearPushRegistration(token)
            GoogleSignIn.signOut(getApplication())
            Log.i(TAG, "Signed out")
            refresh()
        }
    }

    private fun exchangeSession(user: FirebaseUser) {
        _state.value = SignInUiState.Working
        viewModelScope.launch {
            val next = sessionState(user)
            _state.value = next
            _restoring.value = false
            if (next is SignInUiState.SignedIn) {
                requestProfileSync(pushLocal = false)
            }
        }
    }

    private suspend fun sessionState(user: FirebaseUser): SignInUiState {
        var ward = WardCatalog.DEFAULT
        var language = appLanguage.read()
        var role = "Citizen"
        val note = try {
            val idToken = user.getIdToken(false).await().token
                ?: throw SessionExchangeException("Firebase did not return an ID token.")
            val session = SessionClient.exchange(idToken)
            accessToken = session.accessToken
            val name = user.displayName?.takeIf { it.isNotBlank() } ?: FALLBACK_DISPLAY_NAME
            val email = user.email.orEmpty()
            val serverWard = WardCatalog.normalize(session.user.defaultWardCode)
            val pending = notificationStore.isPendingSync()
            ward = if (pending) sessionStore.read()?.defaultWardCode ?: serverWard else serverWard
            if (!pending) {
                val serverLanguage = session.user.preferredLanguage
                if (serverLanguage == LanguageCodes.ENGLISH || serverLanguage == LanguageCodes.ZULU) {
                    language = LanguageCodes.normalize(serverLanguage)
                }
            }
            sessionStore.save(session, name, email, ward, language)
            if (appLanguage.save(language)) {
                Log.i(TAG, "Preferred language set to $language")
                _appliedLanguage.value = language
            }
            role = session.user.roles?.firstOrNull { it.isNotBlank() } ?: "Citizen"
            if (!pending) {
                applyRemoteNotifications(session.user.notifications)
            }
            Log.i(TAG, "API session issued for user ${session.user.id}")
            registerPushToken(session.accessToken)
            getApplication<Application>().getString(R.string.session_ready)
        } catch (error: Exception) {
            accessToken = null
            sessionStore.clear()
            ward = WardCatalog.DEFAULT
            Log.e(TAG, "API session exchange failed: ${error.javaClass.simpleName}")
            if (error is SessionExchangeException) error.message else {
                getApplication<Application>().getString(R.string.session_failed)
            }
        }
        return signedIn(
            user.displayName,
            user.email,
            note ?: getApplication<Application>().getString(R.string.session_failed),
            ward,
            preferredLanguage = language,
            role = role,
        )
    }

    private fun requestProfileSync(pushLocal: Boolean) {
        val token = accessToken ?: return
        viewModelScope.launch {
            profileMutex.withLock {
                val shouldPush = pushLocal || notificationStore.isPendingSync()
                try {
                    val profile = if (shouldPush) {
                        ProfileClient.patch(token, currentPatchBody())
                    } else {
                        ProfileClient.get(token)
                    }
                    applyRemote(profile)
                } catch (error: Exception) {
                    Log.e(TAG, "Profile sync failed: ${error.javaClass.simpleName}")
                    if (shouldPush) {
                        notificationStore.setPendingSync(true)
                        val current = _state.value as? SignInUiState.SignedIn ?: return@withLock
                        _state.value = current.copy(
                            profileNote = getApplication<Application>().getString(R.string.profile_sync_failed),
                        )
                    }
                }
            }
        }
    }

    private fun currentPatchBody(): PatchMeBody {
        val ward = (_state.value as? SignInUiState.SignedIn)?.defaultWardCode ?: WardCatalog.DEFAULT
        val notes = _notifications.value
        return PatchMeBody(
            defaultWardCode = ward,
            preferredLanguage = sessionStore.readLanguage() ?: appLanguage.read(),
            notifications = ApiNotifications(notes.ticketStatus, notes.areaEmergencies),
        )
    }

    private fun applyRemote(profile: UserProfileDto) {
        val ward = WardCatalog.normalize(profile.defaultWardCode)
        sessionStore.updateWard(ward)
        val localLanguage = sessionStore.readLanguage() ?: appLanguage.read()
        val language = if (notificationStore.isPendingSync()) {
            persistLanguage(localLanguage)
        } else {
            persistLanguage(profile.preferredLanguage ?: localLanguage)
        }
        applyRemoteNotifications(profile.notifications)
        notificationStore.setPendingSync(false)
        val current = _state.value as? SignInUiState.SignedIn
        if (current != null) {
            _state.value = current.copy(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: current.displayName,
                email = profile.email?.takeIf { it.isNotBlank() } ?: current.email,
                defaultWardCode = ward,
                profileNote = "",
                preferredLanguage = language,
                role = profile.roles?.firstOrNull { it.isNotBlank() } ?: current.role,
            )
        }
        Log.i(TAG, "Profile synced for user ${profile.id}")
    }

    private fun applyRemoteNotifications(remote: ApiNotifications?) {
        if (remote == null) {
            return
        }
        val merged = _notifications.value.copy(
            ticketStatus = remote.ticketStatus,
            areaEmergencies = remote.areaEmergencies,
        )
        notificationStore.save(merged)
        _notifications.value = merged
    }

    suspend fun submitIncident(
        draft: IncidentDraft,
        photos: List<Uri>,
        clientMutationId: String,
    ): IncidentSubmitResult {
        val token = accessToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Incident create skipped. No API session")
            return failed(R.string.report_auth)
        }
        val mutationId = clientMutationId.trim()
        if (mutationId.length !in 1..80) {
            Log.w(TAG, "Incident create skipped. Mutation id was rejected")
            return failed(R.string.report_send_failed)
        }
        val errors = IncidentValidator.validate(draft)
        val latitude = draft.latitude
        val longitude = draft.longitude
        if (errors.isNotEmpty() || photos.size != draft.photoCount || latitude == null || longitude == null) {
            Log.i(TAG, "Create form rejected. Fields ${errors.keys.joinToString(",")}")
            return failed(R.string.validation_summary)
        }
        if (!DeviceNetwork.isOnline(getApplication())) {
            return queueOffline(draft, photos, mutationId)
        }
        val jpeg = mutableListOf<ByteArray>()
        for (uri in photos) {
            val bytes = PhotoFiles.jpegBytes(getApplication(), uri) ?: return failed(R.string.report_photo_failed)
            jpeg += bytes
        }
        return try {
            val photoIds = if (jpeg.isEmpty()) {
                emptyList()
            } else {
                IncidentClient.uploadPhotos(token, jpeg)
            }
            Log.i(TAG, "Uploaded ${photoIds.size} incident photos")
            when (
                val outcome = IncidentClient.create(
                    token,
                    CreateIncidentBody(
                        category = draft.category,
                        description = draft.description.trim(),
                        latitude = latitude,
                        longitude = longitude,
                        accuracyMeters = draft.accuracyMeters,
                        wardCode = draft.wardCode,
                        photoIds = photoIds,
                        clientMutationId = mutationId,
                    ),
                )
            ) {
                is IncidentCreateOutcome.Created -> {
                    Log.i(TAG, "Incident created ${outcome.incidentId}")
                    IncidentSubmitResult.Created(outcome.incidentId)
                }
                IncidentCreateOutcome.Duplicate -> {
                    Log.i(TAG, "Incident create duplicate")
                    IncidentSubmitResult.AlreadySubmitted
                }
            }
        } catch (error: Exception) {
            val reason = if (error is IncidentRequestException) error.code else error.javaClass.simpleName
            Log.e(TAG, "Incident create failed: $reason")
            val message = when (reason) {
                "UPLOAD_FAILED", "STORAGE_UNAVAILABLE", "PAYLOAD_TOO_LARGE" -> R.string.report_photo_upload
                else -> R.string.report_send_failed
            }
            failed(message)
        }
    }

    private suspend fun queueOffline(
        draft: IncidentDraft,
        photos: List<Uri>,
        mutationId: String,
    ): IncidentSubmitResult {
        return try {
            val paths = QueuedPhotos.store(getApplication(), mutationId, photos)
                ?: return failed(R.string.report_photo_failed)
            val operation = QueuedIncident.operation(draft, mutationId, paths, System.currentTimeMillis())
                ?: return failed(R.string.validation_summary)
            MuniPulseDatabase.get(getApplication()).pendingSync().upsert(operation)
            Log.i(TAG, "Incident queued ${operation.id}. Photos ${paths.size}. Status ${operation.status}")
            IncidentSubmitResult.Queued
        } catch (error: Exception) {
            Log.e(TAG, "Incident queue failed: ${error.javaClass.simpleName}")
            failed(R.string.report_send_failed)
        }
    }

    private fun failed(message: Int): IncidentSubmitResult.Failed =
        IncidentSubmitResult.Failed(getApplication<Application>().getString(message))

    private fun signedIn(
        displayName: String?,
        email: String?,
        sessionNote: String,
        defaultWardCode: String,
        preferredLanguage: String = "en",
        role: String = "Citizen",
    ): SignInUiState.SignedIn =
        SignInUiState.SignedIn(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: FALLBACK_DISPLAY_NAME,
            email = email?.takeIf { it.isNotBlank() } ?: "",
            sessionNote = sessionNote,
            defaultWardCode = WardCatalog.normalize(defaultWardCode),
            preferredLanguage = LanguageCodes.normalize(preferredLanguage),
            role = role.ifBlank { "Citizen" },
        )

    private fun registerPushToken(accessToken: String) {
        viewModelScope.launch {
            try {
                val token = pushTokenStore.read()
                    ?: FirebaseMessaging.getInstance().token.await().also { pushTokenStore.save(it) }
                if (token.isBlank()) {
                    Log.w(TAG, "FCM token was empty")
                    return@launch
                }
                PushClient.register(accessToken, token)
                Log.i(TAG, "FCM token registered")
            } catch (error: Exception) {
                Log.e(TAG, "FCM token register failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun clearPushRegistration(accessToken: String?) {
        try {
            if (!accessToken.isNullOrBlank()) {
                PushClient.clear(accessToken)
                Log.i(TAG, "FCM token cleared on the API")
            }
        } catch (error: Exception) {
            Log.e(TAG, "FCM token clear failed: ${error.javaClass.simpleName}")
        }
        pushTokenStore.clear()
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
            Log.i(TAG, "FCM token deleted on this phone")
        } catch (error: Exception) {
            Log.e(TAG, "FCM token delete failed: ${error.javaClass.simpleName}")
        }
    }

    private fun mirrorSettings() {
        viewModelScope.launch {
            try {
                settingsMirror.refresh()
            } catch (error: Exception) {
                Log.e(TAG, "Settings mirror failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun persistLanguage(code: String): String {
        val next = LanguageCodes.normalize(code)
        sessionStore.updateLanguage(next)
        if (appLanguage.save(next)) {
            Log.i(TAG, "Preferred language set to $next")
            _appliedLanguage.value = next
        }
        mirrorSettings()
        return next
    }

    private companion object {
        const val TAG = "MuniPulseAuth"

        fun placeLabel(description: String): String {
            val line = description.trim().replace(Regex("\\s+"), " ")
            if (line.isEmpty()) {
                return ""
            }
            return if (line.length <= 40) line else line.take(40).trimEnd() + "…"
        }
    }
}
