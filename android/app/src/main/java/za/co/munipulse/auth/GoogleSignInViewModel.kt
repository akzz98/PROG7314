package za.co.munipulse.auth

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import za.co.munipulse.R
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
import za.co.munipulse.ui.incidents.MyIncidentItem
import za.co.munipulse.ui.incidents.MyIncidentsUi
import za.co.munipulse.ui.incidents.IncidentTime
import za.co.munipulse.ui.incidents.TimelineLine
import za.co.munipulse.ui.incidents.WardHotspotsUi

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

class GoogleSignInViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Checking)
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(application)
    private val onboardingStore = OnboardingStore(application)
    private val notificationStore = NotificationStore(application)
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
    private val _upvoteBusy = MutableStateFlow(false)
    val upvoteBusy: StateFlow<Boolean> = _upvoteBusy.asStateFlow()
    private var upvoteInFlight = false

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
                preferredLanguage = sessionStore.readLanguage() ?: "en",
            )
            requestProfileSync(pushLocal = false)
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
                    upvoteCount = body.upvoteCount,
                    viewerHasUpvoted = body.viewerHasUpvoted,
                    grouped = !body.aggregateId.isNullOrBlank(),
                    photoIds = body.photos.orEmpty()
                        .filter { it.id.isNotBlank() && !it.url.isNullOrBlank() }
                        .map { it.id },
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

    suspend fun loadPhotoJpeg(photoId: String): ByteArray? {
        val token = accessToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Incident photo skipped. No API session")
            return null
        }
        return try {
            IncidentClient.photoJpeg(token, photoId)
        } catch (error: Exception) {
            Log.e(TAG, "Incident photo failed: ${error.javaClass.simpleName}")
            null
        }
    }

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
        accessToken = null
        sessionStore.clear()
        notificationStore.setPendingSync(false)
        GoogleSignIn.signOut(getApplication())
        Log.i(TAG, "Signed out")
        refresh()
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
        var language = sessionStore.readLanguage() ?: "en"
        var role = "Citizen"
        val note = try {
            val idToken = user.getIdToken(false).await().token
                ?: throw SessionExchangeException("Firebase did not return an ID token.")
            val session = SessionClient.exchange(idToken)
            accessToken = session.accessToken
            val name = user.displayName?.takeIf { it.isNotBlank() } ?: "Google account"
            val email = user.email.orEmpty()
            val serverWard = WardCatalog.normalize(session.user.defaultWardCode)
            val pending = notificationStore.isPendingSync()
            ward = if (pending) sessionStore.read()?.defaultWardCode ?: serverWard else serverWard
            sessionStore.save(session, name, email, ward)
            role = session.user.roles?.firstOrNull { it.isNotBlank() } ?: "Citizen"
            if (!pending) {
                val serverLanguage = session.user.preferredLanguage
                if (serverLanguage == "en" || serverLanguage == "zu") {
                    sessionStore.updateLanguage(serverLanguage)
                    language = serverLanguage
                }
                applyRemoteNotifications(session.user.notifications)
            }
            Log.i(TAG, "API session issued for user ${session.user.id}")
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
            preferredLanguage = sessionStore.readLanguage(),
            notifications = ApiNotifications(notes.ticketStatus, notes.areaEmergencies),
        )
    }

    private fun applyRemote(profile: UserProfileDto) {
        val ward = WardCatalog.normalize(profile.defaultWardCode)
        sessionStore.updateWard(ward)
        profile.preferredLanguage?.let(sessionStore::updateLanguage)
        applyRemoteNotifications(profile.notifications)
        notificationStore.setPendingSync(false)
        val current = _state.value as? SignInUiState.SignedIn
        if (current != null) {
            _state.value = current.copy(
                displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: current.displayName,
                email = profile.email?.takeIf { it.isNotBlank() } ?: current.email,
                defaultWardCode = ward,
                profileNote = "",
                preferredLanguage = profile.preferredLanguage?.takeIf { it == "en" || it == "zu" }
                    ?: current.preferredLanguage,
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
            displayName = displayName?.takeIf { it.isNotBlank() } ?: "Google account",
            email = email?.takeIf { it.isNotBlank() } ?: "",
            sessionNote = sessionNote,
            defaultWardCode = WardCatalog.normalize(defaultWardCode),
            preferredLanguage = if (preferredLanguage == "zu") "zu" else "en",
            role = role.ifBlank { "Citizen" },
        )

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
