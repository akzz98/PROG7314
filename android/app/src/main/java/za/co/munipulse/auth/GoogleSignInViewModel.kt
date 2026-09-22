package za.co.munipulse.auth

import android.app.Activity
import android.app.Application
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

    fun signOut() {
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
    }
}
