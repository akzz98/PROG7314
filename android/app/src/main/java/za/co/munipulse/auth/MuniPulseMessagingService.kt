package za.co.munipulse.auth

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Keeps the registration id for this phone. Delivery of ticket pushes is a later item.
class MuniPulseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        PushTokenStore(this).save(token)
        Log.i(TAG, "FCM token refreshed")
        val accessToken = SessionStore(this).read()?.accessToken
        if (accessToken.isNullOrBlank()) {
            return
        }
        scope.launch {
            try {
                PushClient.register(accessToken, token)
                Log.i(TAG, "FCM token registered after refresh")
            } catch (error: Exception) {
                Log.e(TAG, "FCM token register failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "MuniPulsePush"
    }
}
