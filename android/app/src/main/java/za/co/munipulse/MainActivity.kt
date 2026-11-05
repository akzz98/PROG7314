package za.co.munipulse

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import za.co.munipulse.auth.AppLanguage
import za.co.munipulse.ui.MuniPulseApp
import za.co.munipulse.ui.theme.MuniPulseTheme

class MainActivity : FragmentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage(newBase).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created")
        enableEdgeToEdge()
        setContent {
            MuniPulseTheme {
                MuniPulseApp()
            }
        }
    }

    private companion object {
        const val TAG = "MuniPulse"
    }
}
