package za.co.munipulse.ui.report

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import za.co.munipulse.R
import za.co.munipulse.report.IncidentCategories
import za.co.munipulse.report.IncidentDraft
import za.co.munipulse.report.IncidentSubmitResult
import za.co.munipulse.report.IncidentValidator
import za.co.munipulse.report.LocationCapture
import za.co.munipulse.report.LocationFix
import za.co.munipulse.report.MediaPermissions
import za.co.munipulse.report.PhotoFiles
import za.co.munipulse.ui.home.categoryName

@Composable
fun CreateIncidentScreen(
    wardCode: String,
    nearby: NearbyUi,
    onLookupNearby: (String, Double, Double, String) -> Unit,
    onClearNearby: () -> Unit,
    onSubmit: suspend (IncidentDraft, List<Uri>, String) -> IncidentSubmitResult,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf(IncidentCategories.DEFAULT) }
    var categoryMenu by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val latestPhotos by rememberUpdatedState(photos)
    var photoLimit by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    var pendingTake by remember { mutableStateOf(false) }
    var formAccepted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitNote by remember { mutableStateOf<String?>(null) }
    var submitFailed by remember { mutableStateOf(false) }
    val mutationId = remember { java.util.UUID.randomUUID().toString() }
    var fieldErrors by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var access by remember { mutableStateOf(MediaPermissions.current(context)) }
    var asked by remember { mutableStateOf(false) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture
        pendingCapture = null
        if (saved && uri != null) {
            photos = (latestPhotos + uri).take(IncidentCategories.MAX_PHOTOS)
            photoLimit = photos.size >= IncidentCategories.MAX_PHOTOS
            Log.i(TAG, "Photo captured. Count ${photos.size}")
        } else {
            Log.i(TAG, "Photo capture cancelled")
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            Log.i(TAG, "Gallery photo cancelled")
            return@rememberLauncherForActivityResult
        }
        if (latestPhotos.size >= IncidentCategories.MAX_PHOTOS) {
            photoLimit = true
            return@rememberLauncherForActivityResult
        }
        photos = latestPhotos + uri
        photoLimit = photos.size >= IncidentCategories.MAX_PHOTOS
        Log.i(TAG, "Gallery photo added. Count ${photos.size}")
    }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        access = try {
            MediaPermissions.fromResult(result, context)
        } catch (error: Exception) {
            Log.e(TAG, "Permission result failed: ${error.javaClass.simpleName}")
            MediaPermissions.current(context)
        }
        asked = true
        if (pendingTake && access.camera) {
            pendingTake = false
            launchCamera(context, onUri = { pendingCapture = it }, onLaunch = takePhoto::launch)
        } else {
            pendingTake = false
        }
    }

    var readingLocation by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var locationMissing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<LocationFix?>(null) }
    var nearbyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(category, fix?.latitude, fix?.longitude, wardCode) {
        val currentFix = fix
        if (currentFix == null) {
            nearbyOpen = false
            onClearNearby()
            return@LaunchedEffect
        }
        delay(400)
        onLookupNearby(category, currentFix.latitude, currentFix.longitude, wardCode)
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = try {
            LocationCapture.fromResult(result, context)
        } catch (error: Exception) {
            Log.e(TAG, "Location permission result failed: ${error.javaClass.simpleName}")
            false
        }
        if (!granted) {
            locationDenied = true
            return@rememberLauncherForActivityResult
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
    }

    LaunchedEffect(Unit) {
        Log.i(TAG, "Opened create incident")
    }
    BackHandler(onBack = onBack)

    val selected = IncidentCategories.all.firstOrNull { it.code == category }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.report_close))
        }
        Text(text = stringResource(R.string.report_title), style = MaterialTheme.typography.headlineMedium)
        Text(text = stringResource(R.string.report_category), style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { categoryMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(selected?.label ?: R.string.category_pothole))
            }
            DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                IncidentCategories.all.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = stringResource(option.label)) },
                        onClick = {
                            if (IncidentCategories.isKnown(option.code)) {
                                category = option.code
                                Log.i(TAG, "Category set to ${option.code}")
                            }
                            categoryMenu = false
                        },
                    )
                }
            }
        }
        FieldMessage(fieldErrors["category"])
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(text = stringResource(R.string.report_description)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            supportingText = { FieldMessage(fieldErrors["description"]) },
            isError = fieldErrors.containsKey("description"),
        )
        Text(text = stringResource(R.string.media_rationale), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    photoLimit = photos.size >= IncidentCategories.MAX_PHOTOS
                    if (photoLimit) {
                        return@OutlinedButton
                    }
                    if (!MediaPermissions.current(context).camera) {
                        pendingTake = true
                        try {
                            mediaLauncher.launch(MediaPermissions.request())
                        } catch (error: Exception) {
                            pendingTake = false
                            asked = true
                            Log.e(TAG, "Permission request failed: ${error.javaClass.simpleName}")
                        }
                        return@OutlinedButton
                    }
                    launchCamera(context, onUri = { pendingCapture = it }, onLaunch = takePhoto::launch)
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.report_take_photo))
            }
            OutlinedButton(
                onClick = {
                    if (photos.size >= IncidentCategories.MAX_PHOTOS) {
                        photoLimit = true
                        return@OutlinedButton
                    }
                    try {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    } catch (error: Exception) {
                        Log.e(TAG, "Gallery open failed: ${error.javaClass.simpleName}")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.report_gallery))
            }
        }
        if (asked && !access.camera) {
            Text(
                text = stringResource(R.string.media_camera_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (photoLimit) {
            Text(text = stringResource(R.string.report_photo_limit), style = MaterialTheme.typography.bodyMedium)
        }
        FieldMessage(fieldErrors["photoIds"])
        photos.forEach { uri ->
            PhotoThumb(
                uri = uri,
                onRemove = {
                    val next = photos - uri
                    photos = next
                    photoLimit = next.size >= IncidentCategories.MAX_PHOTOS
                },
            )
        }
        Text(text = stringResource(R.string.location_rationale), style = MaterialTheme.typography.bodyMedium)
        val captured = fix
        if (captured != null) {
            Text(
                text = stringResource(
                    R.string.location_fix,
                    captured.latitude,
                    captured.longitude,
                    captured.accuracyMeters,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
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
            Text(
                text = stringResource(
                    when {
                        readingLocation -> R.string.location_reading
                        fix == null -> R.string.location_allow
                        else -> R.string.location_refresh
                    },
                ),
            )
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
        FieldMessage(fieldErrors["latitude"])
        FieldMessage(fieldErrors["longitude"])
        FieldMessage(fieldErrors["accuracyMeters"])
        FieldMessage(fieldErrors["wardCode"])
        Spacer(modifier = Modifier.height(8.dp))
        NearbyDuplicates(nearby, nearbyOpen, onToggle = { nearbyOpen = !nearbyOpen })
        Button(
            onClick = {
                if (submitting) {
                    return@Button
                }
                val draft = IncidentDraft(
                    category = category,
                    description = description,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                    wardCode = wardCode,
                    photoCount = photos.size,
                )
                val errors = IncidentValidator.validate(draft)
                fieldErrors = errors
                formAccepted = false
                submitNote = null
                submitFailed = false
                if (errors.isNotEmpty()) {
                    Log.i(TAG, "Create form rejected. Fields ${errors.keys.joinToString(",")}")
                    return@Button
                }
                submitting = true
                val photoUris = photos
                scope.launch {
                    val result = try {
                        onSubmit(draft, photoUris, mutationId)
                    } catch (error: Exception) {
                        Log.e(TAG, "Incident create failed: ${error.javaClass.simpleName}")
                        IncidentSubmitResult.Failed(context.getString(R.string.report_send_failed))
                    }
                    submitting = false
                    when (result) {
                        is IncidentSubmitResult.Created -> {
                            formAccepted = true
                            submitFailed = false
                            submitNote = context.getString(R.string.report_saved, result.incidentId)
                        }
                        IncidentSubmitResult.AlreadySubmitted -> {
                            formAccepted = true
                            submitFailed = false
                            submitNote = context.getString(R.string.report_duplicate)
                        }
                        is IncidentSubmitResult.Failed -> {
                            formAccepted = false
                            submitFailed = true
                            submitNote = result.message
                        }
                    }
                }
            },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(if (submitting) R.string.report_sending else R.string.report_submit))
        }
        if (fieldErrors.isNotEmpty()) {
            Text(
                text = stringResource(R.string.validation_summary),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val note = submitNote
        if (note != null) {
            Text(
                text = note,
                color = if (submitFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NearbyDuplicates(nearby: NearbyUi, expanded: Boolean, onToggle: () -> Unit) {
    when (nearby) {
        NearbyUi.Idle -> Unit
        NearbyUi.Loading -> Text(text = stringResource(R.string.nearby_checking), style = MaterialTheme.typography.bodyMedium)
        is NearbyUi.Failed -> Text(
            text = nearby.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        is NearbyUi.Ready -> {
            if (nearby.items.isEmpty()) {
                Text(text = stringResource(R.string.nearby_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                TextButton(onClick = onToggle) {
                    Text(text = stringResource(R.string.nearby_title, nearby.items.size) + if (expanded) " ▴" else " ▾")
                }
                if (expanded) {
                    nearby.items.forEach { item ->
                        val category = categoryName(item.category)
                        val line = if (item.place.isBlank()) {
                            stringResource(R.string.nearby_row_plain, category, item.upvoteCount, item.distanceMeters)
                        } else {
                            stringResource(R.string.nearby_row, category, item.place, item.upvoteCount, item.distanceMeters)
                        }
                        Text(text = line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun FieldMessage(message: Int?) {
    if (message == null) {
        return
    }
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PhotoThumb(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { PhotoFiles.thumbnail(context, uri) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val image = bitmap
        if (image == null) {
            Text(
                text = stringResource(R.string.report_photo_unreadable),
                modifier = Modifier.size(96.dp),
            )
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.report_photo_preview),
                modifier = Modifier.size(96.dp),
            )
        }
        TextButton(onClick = onRemove) {
            Text(text = stringResource(R.string.report_photo_remove))
        }
    }
}

private fun launchCamera(
    context: android.content.Context,
    onUri: (Uri) -> Unit,
    onLaunch: (Uri) -> Unit,
) {
    val uri = PhotoFiles.newCaptureUri(context)
    if (uri == null) {
        Log.e(TAG, "Photo capture did not start")
        return
    }
    try {
        onUri(uri)
        onLaunch(uri)
    } catch (error: Exception) {
        Log.e(TAG, "Photo capture failed: ${error.javaClass.simpleName}")
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

private const val TAG = "MuniPulse"
