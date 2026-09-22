package za.co.munipulse.ui.home

import android.util.Log
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import za.co.munipulse.auth.WardCatalog
import za.co.munipulse.report.IncidentCategories
import za.co.munipulse.ui.incidents.MyIncidentsPane
import za.co.munipulse.ui.incidents.MyIncidentsUi

enum class HomeTab {
    Pulse,
    Mine,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    wardCode: String,
    pulse: WardPulseUi,
    onRefresh: (String) -> Unit,
    onWardSelected: (String) -> Unit,
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    mine: MyIncidentsUi,
    mineStatus: String?,
    onRefreshMine: () -> Unit,
    onMineStatus: (String?) -> Unit,
    onOpenIncident: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var wardMenu by remember { mutableStateOf(false) }
    val ward = WardCatalog.all.firstOrNull { it.code == wardCode }

    LaunchedEffect(wardCode) {
        onRefresh(wardCode)
    }
    LaunchedEffect(tab) {
        Log.i(TAG, "Home tab ${tab.name}")
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenReport,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(text = stringResource(R.string.home_report)) },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.Pulse,
                    onClick = { onTab(HomeTab.Pulse) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.nav_home)) },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.Mine,
                    onClick = { onTab(HomeTab.Mine) },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.nav_my)) },
                )
                // FINAL POE — the emergency map is not part of this prototype.
                NavigationBarItem(
                    selected = false,
                    onClick = {},
                    enabled = false,
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.nav_map)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.nav_settings)) },
                )
            }
        },
    ) { padding ->
        if (tab == HomeTab.Mine) {
            MyIncidentsPane(
                padding = padding,
                mine = mine,
                status = mineStatus,
                onRefresh = onRefreshMine,
                onStatus = onMineStatus,
                onOpenIncident = onOpenIncident,
            )
        } else {
            PulseBody(
                padding = padding,
                wardLabel = ward?.name ?: wardCode,
                wardMenu = wardMenu,
                onWardMenu = { wardMenu = it },
                onWardSelected = onWardSelected,
                onOpenNotifications = onOpenNotifications,
                onOpenProfile = onOpenProfile,
                pulse = pulse,
                onRetry = { onRefresh(wardCode) },
                onOpenIncident = onOpenIncident,
            )
        }
    }
}

@Composable
private fun PulseBody(
    padding: PaddingValues,
    wardLabel: String,
    wardMenu: Boolean,
    onWardMenu: (Boolean) -> Unit,
    onWardSelected: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
    pulse: WardPulseUi,
    onRetry: () -> Unit,
    onOpenIncident: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { onWardMenu(true) }) {
                    Text(text = stringResource(R.string.pulse_ward, wardLabel))
                }
                DropdownMenu(expanded = wardMenu, onDismissRequest = { onWardMenu(false) }) {
                    WardCatalog.all.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = "${option.name} · ${option.municipality}") },
                            onClick = {
                                onWardMenu(false)
                                onWardSelected(option.code)
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenNotifications) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = stringResource(R.string.nav_notifications),
                )
            }
            IconButton(onClick = onOpenProfile) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = stringResource(R.string.nav_profile),
                )
            }
        }
        Text(
            text = stringResource(R.string.pulse_open, openCount(pulse)),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        when (pulse) {
            WardPulseUi.Idle, WardPulseUi.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is WardPulseUi.Failed -> {
                Text(
                    text = pulse.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.pulse_retry))
                }
            }
            is WardPulseUi.Ready -> {
                if (pulse.items.isEmpty()) {
                    Text(text = stringResource(R.string.pulse_empty), style = MaterialTheme.typography.bodyLarge)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pulse.items, key = { it.id }) { item ->
                            PulseCard(item, onOpen = { onOpenIncident(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseCard(item: WardPulseItem, onOpen: () -> Unit) {
    val category = categoryName(item.category)
    val title = if (item.place.isBlank()) category else "$category · ${item.place}"
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.pulse_upvote, item.upvoteCount),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
internal fun categoryName(code: String): String {
    val match = IncidentCategories.all.firstOrNull { it.code == code } ?: return code
    return stringResource(match.label)
}

private fun openCount(pulse: WardPulseUi): Int = if (pulse is WardPulseUi.Ready) pulse.openCount else 0

private const val TAG = "MuniPulseHome"
