package za.co.munipulse.ui.incidents

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import za.co.munipulse.R
import za.co.munipulse.report.PhotoFiles
import za.co.munipulse.ui.home.categoryName

@Composable
fun IncidentDetailScreen(
    incidentId: String,
    detail: IncidentDetailUi,
    onLoad: (String) -> Unit,
    onLoadPhoto: suspend (String) -> ByteArray?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(incidentId) {
        onLoad(incidentId)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
        }
        when (detail) {
            IncidentDetailUi.Idle, IncidentDetailUi.Loading -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is IncidentDetailUi.Failed -> {
                Text(text = detail.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { onLoad(incidentId) }) {
                    Text(text = stringResource(R.string.pulse_retry))
                }
            }
            is IncidentDetailUi.Ready -> DetailBody(detail.item, onLoadPhoto)
        }
    }
}

@Composable
private fun DetailBody(item: IncidentDetailItem, onLoadPhoto: suspend (String) -> ByteArray?) {
    val category = categoryName(item.category)
    val title = if (item.place.isBlank()) category else "$category · ${item.place}"
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = stringResource(R.string.detail_status, statusLabel(item.status)), style = MaterialTheme.typography.titleMedium)
    Text(text = stringResource(R.string.pulse_upvote, item.upvoteCount), style = MaterialTheme.typography.titleMedium)
    if (item.description.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = item.description, style = MaterialTheme.typography.bodyLarge)
    }
    if (item.photoIds.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.photoIds.forEach { photoId ->
                DetailPhoto(photoId, onLoadPhoto)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.detail_timeline), style = MaterialTheme.typography.titleMedium)
    if (item.timeline.isEmpty()) {
        Text(text = stringResource(R.string.detail_timeline_empty), style = MaterialTheme.typography.bodyLarge)
    } else {
        item.timeline.forEach { line ->
            val clock = IncidentTime.clockLabel(line.at)
            val label = timelineLabel(line.type)
            val heading = if (clock.isBlank()) label else "$label — $clock"
            Text(text = heading, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            if (line.note.isNotBlank()) {
                Text(text = line.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    // FINAL POE — the map snippet is not part of this prototype.
    Text(text = stringResource(R.string.detail_map), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DetailPhoto(photoId: String, onLoadPhoto: suspend (String) -> ByteArray?) {
    var bitmap by remember(photoId) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(photoId) { mutableStateOf(false) }
    LaunchedEffect(photoId) {
        val bytes = onLoadPhoto(photoId)
        val decoded = if (bytes == null) null else withContext(Dispatchers.IO) { PhotoFiles.thumbnail(bytes) }
        bitmap = decoded
        failed = decoded == null
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = stringResource(R.string.report_photo_preview),
            modifier = Modifier.size(160.dp),
        )
    } else if (failed) {
        Text(text = stringResource(R.string.report_photo_unreadable), style = MaterialTheme.typography.bodyMedium)
    } else {
        Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun timelineLabel(type: String): String = when (type) {
    "Submitted" -> stringResource(R.string.timeline_logged)
    "Assigned" -> stringResource(R.string.timeline_assigned)
    "OnSite" -> stringResource(R.string.timeline_on_site)
    "Resolved" -> stringResource(R.string.timeline_resolved)
    "Note" -> stringResource(R.string.timeline_note)
    else -> type
}
