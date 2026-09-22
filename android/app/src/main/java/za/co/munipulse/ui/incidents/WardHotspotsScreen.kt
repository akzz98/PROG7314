package za.co.munipulse.ui.incidents

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.WardCatalog
import za.co.munipulse.ui.common.ListEmpty
import za.co.munipulse.ui.common.ListError
import za.co.munipulse.ui.common.ListLoading
import za.co.munipulse.ui.home.WardPulseItem
import za.co.munipulse.ui.home.WardPulseUi
import za.co.munipulse.ui.home.categoryName

@Composable
fun WardHotspotsScreen(
    wardCode: String,
    pulse: WardPulseUi,
    onRefresh: (String) -> Unit,
    onOpenIncident: (String) -> Unit,
    onOpenReport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ward = WardCatalog.all.firstOrNull { it.code == wardCode }
    BackHandler(onBack = onBack)
    LaunchedEffect(wardCode) {
        Log.i(TAG, "Opened ward hotspots. Ward $wardCode")
        onRefresh(wardCode)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
        }
        Text(
            text = stringResource(R.string.hotspots_title, ward?.name ?: wardCode),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.hotspots_note), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        when (pulse) {
            WardPulseUi.Idle, WardPulseUi.Loading -> ListLoading()
            is WardPulseUi.Failed -> ListError(message = pulse.message, onRetry = { onRefresh(wardCode) })
            is WardPulseUi.Ready -> {
                if (pulse.items.isEmpty()) {
                    ListEmpty(
                        title = stringResource(R.string.pulse_empty),
                        body = stringResource(R.string.list_empty_body),
                        actionLabel = stringResource(R.string.home_report),
                        onAction = onOpenReport,
                    )
                } else {
                    HotspotList(pulse.items, onOpenIncident)
                }
            }
        }
    }
}

@Composable
private fun HotspotList(items: List<WardPulseItem>, onOpenIncident: (String) -> Unit) {
    val rows = remember(items) { hotspotRows(items) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.category }) { row ->
            val category = categoryName(row.category)
            val title = if (row.reportCount == 1) {
                stringResource(R.string.hotspots_row, row.rank, category, row.upvoteCount)
            } else {
                stringResource(R.string.hotspots_row_count, row.rank, category, row.reportCount, row.upvoteCount)
            }
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenIncident(row.incidentId) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun hotspotRows(items: List<WardPulseItem>): List<HotspotRow> =
    items.groupBy { it.category }
        .map { (category, group) ->
            val top = group.maxWith(compareByDescending<WardPulseItem> { it.upvoteCount }.thenBy { it.id })
            HotspotRow(
                category = category,
                reportCount = group.size,
                upvoteCount = group.sumOf { it.upvoteCount },
                incidentId = top.id,
            )
        }
        .sortedWith(compareByDescending<HotspotRow> { it.upvoteCount }.thenBy { it.category })
        .mapIndexed { index, row -> row.copy(rank = index + 1) }

private data class HotspotRow(
    val rank: Int = 0,
    val category: String,
    val reportCount: Int,
    val upvoteCount: Int,
    val incidentId: String,
)

private const val TAG = "MuniPulseHome"
