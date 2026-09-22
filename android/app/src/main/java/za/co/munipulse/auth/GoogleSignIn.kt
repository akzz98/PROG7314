package za.co.munipulse.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

// Google sign-in through Credential Manager, then Firebase Auth.
// https://firebase.google.com/docs/auth/android/google-signin
object GoogleSignIn {
    private const val TAG = "MuniPulseAuth"

    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    fun currentUser(context: Context): FirebaseUser? {
        if (!isConfigured(context)) {
            return null
        }
        return FirebaseAuth.getInstance().currentUser
    }

    suspend fun signIn(activity: Activity): FirebaseUser {
        if (!isConfigured(activity)) {
            throw MissingFirebaseConfigException()
        }
        val webClientId = webClientId(activity)
            ?: throw MissingWebClientException()

        Log.i(TAG, "Google sign-in started")
        val credentialManager = CredentialManager.create(activity)
        val googleId = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val result = try {
            credentialManager.getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(googleId).build(),
            )
        } catch (cancelled: GetCredentialCancellationException) {
            Log.i(TAG, "Google sign-in cancelled")
            throw cancelled
        } catch (missing: NoCredentialException) {
            Log.i(TAG, "No authorised Google account yet; opening the account picker")
            val fallback = GetSignInWithGoogleOption.Builder(webClientId).build()
            credentialManager.getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(fallback).build(),
            )
        }

        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Unexpected credential type")
        }

        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)
        val authResult = FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
        val user = authResult.user ?: throw IllegalStateException("Firebase user was empty")
        Log.i(TAG, "Firebase sign-in succeeded for uid ${user.uid}")
        return user
    }

    fun signOut(context: Context) {
        if (!isConfigured(context)) {
            return
        }
        FirebaseAuth.getInstance().signOut()
        Log.i(TAG, "Firebase sign-out completed")
    }

    fun hasWebClient(context: Context): Boolean = webClientId(context) != null

    private fun webClientId(context: Context): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId == 0) {
            return null
        }
        return context.getString(resId).takeIf { it.isNotBlank() }
    }
}

class MissingFirebaseConfigException : IllegalStateException(
    "Firebase is not configured. Add google-services.json and enable the Google provider.",
)

class MissingWebClientException : IllegalStateException(
    "Google sign-in has no Web client id yet. Enable the provider, add the SHA-1, and download google-services.json again.",
)
