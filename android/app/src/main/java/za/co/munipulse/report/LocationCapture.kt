package za.co.munipulse.report

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
)

// Reads one GPS or network fix after the location permission is granted.
// Coordinates stay off Logcat. Only the permission result and accuracy are logged.
object LocationCapture {
    fun request(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun isGranted(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun fromResult(result: Map<String, Boolean>, context: Context): Boolean {
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            isGranted(context)
        Log.i(TAG, "Location permission ${if (granted) "granted" else "denied"}")
        return granted
    }

    suspend fun capture(context: Context): LocationFix? {
        if (!isGranted(context)) {
            Log.i(TAG, "Location fix skipped. Permission denied")
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            Log.i(TAG, "Location fix unavailable")
            return null
        }
        val fix = try {
            lastKnown(manager) ?: withTimeoutOrNull(FIX_TIMEOUT_MS) { awaitCurrent(context, manager) }
        } catch (error: Exception) {
            Log.e(TAG, "Location capture failed: ${error.javaClass.simpleName}")
            null
        }
        if (fix == null) {
            Log.i(TAG, "Location fix unavailable")
        } else {
            Log.i(TAG, "Location fix captured. Accuracy ${fix.accuracyMeters.toInt()} m")
        }
        return fix
    }

    private fun lastKnown(manager: LocationManager): LocationFix? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        return providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)?.toFix()
            } catch (error: Exception) {
                Log.e(TAG, "Last location failed: ${error.javaClass.simpleName}")
                null
            }
        }.minByOrNull { it.accuracyMeters }
    }

    private suspend fun awaitCurrent(context: Context, manager: LocationManager): LocationFix? =
        suspendCancellableCoroutine { continuation ->
            val provider = providerOrNull(manager)
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location?.toFix())
                        }
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) {
                                continuation.resume(location.toFix())
                            }
                        }
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            } catch (error: Exception) {
                Log.e(TAG, "Location update failed: ${error.javaClass.simpleName}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }

    private fun providerOrNull(manager: LocationManager): String? {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        return providers.firstOrNull { provider ->
            try {
                manager.isProviderEnabled(provider)
            } catch (error: Exception) {
                Log.e(TAG, "Location provider check failed: ${error.javaClass.simpleName}")
                false
            }
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        try {
            ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (error: Exception) {
            Log.e(TAG, "Permission check failed: ${error.javaClass.simpleName}")
            false
        }

    private fun Location.toFix(): LocationFix? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !hasAccuracy() || accuracy < 0f) {
            return null
        }
        return LocationFix(latitude, longitude, accuracy)
    }

    private const val TAG = "MuniPulse"
    private const val FIX_TIMEOUT_MS = 12_000L
}
