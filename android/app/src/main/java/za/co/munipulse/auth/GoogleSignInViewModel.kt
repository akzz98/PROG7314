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
    ) : SignInUiState
    data object MissingConfig : SignInUiState
    data object MissingWebClient : SignInUiState
}

class GoogleSignInViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Checking)
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private val sessionStore = SessionStore(application)
    private val onboardingStore = OnboardingStore(application)
    private var accessToken: String? = null

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(onboardingStore.isComplete())
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

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
            )
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
            _state.value = try {
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
        }
    }

    fun updateDefaultWard(code: String) {
        val current = _state.value as? SignInUiState.SignedIn ?: return
        if (!sessionStore.updateWard(code)) {
            Log.w(TAG, "Ignored unknown ward selection")
            return
        }
        _state.value = current.copy(defaultWardCode = code)
        Log.i(TAG, "Default ward set to $code")
    }

    fun signOut() {
        accessToken = null
        sessionStore.clear()
        GoogleSignIn.signOut(getApplication())
        Log.i(TAG, "Signed out")
        refresh()
    }

    private fun exchangeSession(user: FirebaseUser) {
        _state.value = SignInUiState.Working
        viewModelScope.launch {
            _state.value = sessionState(user)
            _restoring.value = false
        }
    }

    private suspend fun sessionState(user: FirebaseUser): SignInUiState {
        var ward = WardCatalog.DEFAULT
        val note = try {
            val idToken = user.getIdToken(false).await().token
                ?: throw SessionExchangeException("Firebase did not return an ID token.")
            val session = SessionClient.exchange(idToken)
            accessToken = session.accessToken
            val name = user.displayName?.takeIf { it.isNotBlank() } ?: "Google account"
            val email = user.email.orEmpty()
            ward = WardCatalog.normalize(session.user.defaultWardCode)
            sessionStore.save(session, name, email, ward)
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
        )
    }

    private fun signedIn(
        displayName: String?,
        email: String?,
        sessionNote: String,
        defaultWardCode: String,
    ): SignInUiState.SignedIn =
        SignInUiState.SignedIn(
            displayName = displayName?.takeIf { it.isNotBlank() } ?: "Google account",
            email = email?.takeIf { it.isNotBlank() } ?: "",
            sessionNote = sessionNote,
            defaultWardCode = WardCatalog.normalize(defaultWardCode),
        )

    private companion object {
        const val TAG = "MuniPulseAuth"
    }
}
