package za.co.munipulse

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import za.co.munipulse.ui.MuniPulseApp
import za.co.munipulse.ui.theme.MuniPulseTheme

class MainActivity : ComponentActivity() {
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
