package za.co.munipulse.ui.incidents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.ui.home.categoryName

@Composable
fun MyIncidentsPane(
    padding: PaddingValues,
    mine: MyIncidentsUi,
    status: String?,
    onRefresh: () -> Unit,
    onStatus: (String?) -> Unit,
    onOpenIncident: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        onRefresh()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Text(text = stringResource(R.string.mine_title), style = MaterialTheme.typography.headlineMedium)
        Box {
            TextButton(onClick = { menu = true }) {
                Text(text = stringResource(R.string.mine_filter, statusLabel(status)))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(null, "Submitted", "InProgress", "Resolved").forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(text = statusLabel(choice)) },
                        onClick = {
                            menu = false
                            onStatus(choice)
                        },
                    )
                }
            }
        }
        when (mine) {
            MyIncidentsUi.Idle, MyIncidentsUi.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is MyIncidentsUi.Failed -> {
                Text(text = mine.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRefresh) {
                    Text(text = stringResource(R.string.pulse_retry))
                }
            }
            is MyIncidentsUi.Ready -> {
                if (mine.items.isEmpty()) {
                    val empty = if (status == null) R.string.mine_empty else R.string.mine_empty_filter
                    Text(text = stringResource(empty), style = MaterialTheme.typography.bodyLarge)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(mine.items, key = { it.id }) { item ->
                            MineRow(item, onOpen = { onOpenIncident(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MineRow(item: MyIncidentItem, onOpen: () -> Unit) {
    val category = categoryName(item.category)
    val whenLabel = IncidentTime.whenLabel(item.createdAt)
    val title = if (whenLabel.isBlank()) {
        "${statusLabel(item.status)} · $category"
    } else {
        "${statusLabel(item.status)} · $category · $whenLabel"
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun statusLabel(status: String?): String = when (status) {
    null -> stringResource(R.string.mine_all)
    "Submitted" -> stringResource(R.string.status_submitted)
    "InProgress" -> stringResource(R.string.status_in_progress)
    "Resolved" -> stringResource(R.string.status_resolved)
    else -> status
}
