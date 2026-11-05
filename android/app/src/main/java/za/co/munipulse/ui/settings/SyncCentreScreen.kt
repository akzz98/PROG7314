package za.co.munipulse.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.QueuedReportRow
import za.co.munipulse.auth.SyncCentreUi
import za.co.munipulse.data.SyncAttempt
import za.co.munipulse.data.SyncStatus
import za.co.munipulse.report.IncidentCategories
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun SyncCentreScreen(
    centre: SyncCentreUi,
    onRefresh: () -> Unit,
    onRetry: (String) -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        Log.i(TAG, "Opened sync centre")
        onRefresh()
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
        Text(text = stringResource(R.string.settings_sync), style = MaterialTheme.typography.headlineMedium)
        when (centre) {
            SyncCentreUi.Loading -> {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
            }
            is SyncCentreUi.Failed -> Text(
                text = centre.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            is SyncCentreUi.Ready -> SyncCentreBody(centre, onRetry, onSyncNow)
        }
    }
}

@Composable
private fun SyncCentreBody(
    centre: SyncCentreUi.Ready,
    onRetry: (String) -> Unit,
    onSyncNow: () -> Unit,
) {
    val lastSync = centre.lastSyncAt?.let(::clock) ?: stringResource(R.string.sync_never)
    Text(
        text = stringResource(R.string.sync_last, lastSync),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = onSyncNow,
        enabled = !centre.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(if (centre.busy) R.string.sync_working else R.string.sync_now))
    }
    if (centre.note.isNotBlank()) {
        Text(text = centre.note, style = MaterialTheme.typography.bodyMedium)
    }
    Text(text = stringResource(R.string.sync_pending), style = MaterialTheme.typography.titleMedium)
    centre.items.forEach { item ->
        SyncRow(item, enabled = !centre.busy, onRetry = onRetry)
    }
}

@Composable
private fun SyncRow(
    item: QueuedReportRow,
    enabled: Boolean,
    onRetry: (String) -> Unit,
) {
    val category = IncidentCategories.all.firstOrNull { it.code == item.category }?.label
    val categoryLabel = if (category == null) item.category else stringResource(category)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.sync_row, categoryLabel, statusLabel(item.status), item.retryCount),
            style = MaterialTheme.typography.bodyLarge,
        )
        val error = errorLabel(item.errorCode)
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (item.status == SyncStatus.PENDING) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onRetry(item.id) }, enabled = enabled) {
                    Text(text = stringResource(R.string.sync_retry))
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    SyncStatus.ACK -> stringResource(R.string.sync_status_ack)
    SyncStatus.CONFLICT -> stringResource(R.string.sync_status_conflict)
    else -> stringResource(R.string.sync_status_pending)
}

@Composable
private fun errorLabel(code: String?): String? = when (code) {
    SyncAttempt.OFFLINE -> stringResource(R.string.sync_error_offline)
    SyncAttempt.WAITING -> stringResource(R.string.sync_error_waiting)
    null, "" -> null
    else -> stringResource(R.string.sync_error_other)
}

private fun clock(epochMillis: Long): String {
    val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return format.format(local)
}

private const val TAG = "MuniPulse"
