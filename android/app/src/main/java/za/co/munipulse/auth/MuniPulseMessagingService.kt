package za.co.munipulse.auth

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import za.co.munipulse.R

// Keeps the registration id and shows ticket or area alerts. Marketing is never pushed here.
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

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"].orEmpty()
        if (type != PushNotifications.TYPE_TICKET && type != PushNotifications.TYPE_AREA) {
            Log.i(TAG, "Push ignored. Unknown type")
            return
        }
        val title = data["title"]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.push_fallback_title)
        val body = data["body"]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.push_fallback_body)
        PushNotifications.show(
            context = this,
            title = title,
            body = body,
            type = type,
            incidentId = data["incidentId"],
            wardCode = data["wardCode"],
        )
    }

    private companion object {
        const val TAG = "MuniPulsePush"
    }
}
