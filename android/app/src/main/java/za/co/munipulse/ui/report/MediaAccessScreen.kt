package za.co.munipulse.ui.report

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import za.co.munipulse.R
import za.co.munipulse.report.LocationCapture
import za.co.munipulse.report.LocationFix
import za.co.munipulse.report.MediaAccess
import za.co.munipulse.report.MediaPermissions

@Composable
fun MediaAccessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(MediaPermissions.current(context)) }
    var asked by remember { mutableStateOf(access.camera && access.gallery) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        access = try {
            MediaPermissions.fromResult(result, context)
        } catch (error: Exception) {
            Log.e(TAG, "Permission result failed: ${error.javaClass.simpleName}")
            MediaPermissions.current(context)
        }
        asked = true
    }
    var readingLocation by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var locationMissing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<LocationFix?>(null) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = try {
            LocationCapture.fromResult(result, context)
        } catch (error: Exception) {
            Log.e(TAG, "Location permission result failed: ${error.javaClass.simpleName}")
            false
        }
        if (granted) {
            readingLocation = true
            locationDenied = false
            locationMissing = false
            scope.launch {
                applyFix(
                    context,
                    onFix = {
                        fix = it
                        locationMissing = false
                    },
                    onMissing = { locationMissing = true },
                    onDone = { readingLocation = false },
                )
            }
        } else {
            locationDenied = true
            locationMissing = false
            fix = null
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.settings_back))
        }
        Text(text = stringResource(R.string.media_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.media_rationale), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                try {
                    launcher.launch(MediaPermissions.request())
                } catch (error: Exception) {
                    Log.e(TAG, "Permission request failed: ${error.javaClass.simpleName}")
                    asked = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.media_allow))
        }
        if (asked) {
            Text(
                text = stringResource(statusText(access)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (access.camera && access.gallery) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.location_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.location_rationale), style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = {
                locationDenied = false
                locationMissing = false
                if (!LocationCapture.isGranted(context)) {
                    try {
                        locationLauncher.launch(LocationCapture.request())
                    } catch (error: Exception) {
                        Log.e(TAG, "Location permission request failed: ${error.javaClass.simpleName}")
                        locationDenied = true
                    }
                    return@Button
                }
                readingLocation = true
                scope.launch {
                    applyFix(
                        context,
                        onFix = {
                            fix = it
                            locationMissing = false
                        },
                        onMissing = { locationMissing = true },
                        onDone = { readingLocation = false },
                    )
                }
            },
            enabled = !readingLocation,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(if (readingLocation) R.string.location_reading else R.string.location_allow))
        }
        if (locationDenied) {
            Text(
                text = stringResource(R.string.location_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (locationMissing) {
            Text(
                text = stringResource(R.string.location_missing),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val captured = fix
        if (captured != null) {
            Text(
                text = stringResource(
                    R.string.location_fix,
                    captured.latitude,
                    captured.longitude,
                    captured.accuracyMeters,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private suspend fun applyFix(
    context: android.content.Context,
    onFix: (LocationFix) -> Unit,
    onMissing: () -> Unit,
    onDone: () -> Unit,
) {
    val captured = try {
        LocationCapture.capture(context)
    } catch (error: Exception) {
        Log.e(TAG, "Location capture failed: ${error.javaClass.simpleName}")
        null
    }
    if (captured == null) {
        onMissing()
    } else {
        onFix(captured)
    }
    onDone()
}

private fun statusText(access: MediaAccess): Int = when {
    access.camera && access.gallery -> R.string.media_granted
    !access.camera && !access.gallery -> R.string.media_both_denied
    !access.camera -> R.string.media_camera_denied
    else -> R.string.media_gallery_denied
}

private const val TAG = "MuniPulse"
