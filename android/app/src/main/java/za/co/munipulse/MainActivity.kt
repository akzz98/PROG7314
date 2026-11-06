package za.co.munipulse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import za.co.munipulse.auth.AppLanguage
import za.co.munipulse.auth.PushNotifications
import za.co.munipulse.ui.MuniPulseApp
import za.co.munipulse.ui.theme.MuniPulseTheme

data class PushLaunch(
    val type: String,
    val incidentId: String?,
    val wardCode: String?,
)

class MainActivity : FragmentActivity() {
    var pushLaunch by mutableStateOf<PushLaunch?>(null)
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage(newBase).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created")
        pushLaunch = readLaunch(intent)
        enableEdgeToEdge()
        setContent {
            MuniPulseTheme {
                MuniPulseApp(
                    pushLaunch = pushLaunch,
                    onPushConsumed = { pushLaunch = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushLaunch = readLaunch(intent)
        Log.i(TAG, "MainActivity received a push launch")
    }

    private fun readLaunch(intent: Intent?): PushLaunch? {
        val type = intent?.getStringExtra(PushNotifications.EXTRA_TYPE)?.trim().orEmpty()
        if (type != PushNotifications.TYPE_TICKET && type != PushNotifications.TYPE_AREA) {
            return null
        }
        return PushLaunch(
            type = type,
            incidentId = intent?.getStringExtra(PushNotifications.EXTRA_INCIDENT_ID)?.trim()?.takeIf { it.isNotEmpty() },
            wardCode = intent?.getStringExtra(PushNotifications.EXTRA_WARD_CODE)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        const val TAG = "MuniPulse"
    }
}
