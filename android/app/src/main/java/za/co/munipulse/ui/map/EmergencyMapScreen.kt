package za.co.munipulse.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.ui.common.ListEmpty
import za.co.munipulse.ui.common.ListError
import za.co.munipulse.ui.common.ListLoading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val POINT_WATER = "Water"
const val POINT_EVACUATION = "Evacuation"

data class EmergencyPointItem(
    val id: String,
    val kind: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

sealed interface EmergencyMapUi {
    data object Idle : EmergencyMapUi
    data object Loading : EmergencyMapUi
    data class Ready(
        val points: List<EmergencyPointItem>,
        val fetchedAt: Long,
        val fromCache: Boolean,
    ) : EmergencyMapUi

    data object Empty : EmergencyMapUi
    data class Failed(val message: String, val cacheAvailable: Boolean) : EmergencyMapUi
}

@Composable
fun EmergencyMapScreen(
    padding: PaddingValues,
    wardLabel: String,
    map: EmergencyMapUi,
    onRefresh: () -> Unit,
    onNavigateCached: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(wardLabel) {
        onRefresh()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text = stringResource(R.string.map_title, wardLabel), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.map_disclaimer), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(12.dp))
        when (map) {
            EmergencyMapUi.Idle, EmergencyMapUi.Loading -> ListLoading()
            is EmergencyMapUi.Failed -> {
                ListError(message = map.message, onRetry = onRefresh)
                if (map.cacheAvailable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onNavigateCached) {
                        Text(text = stringResource(R.string.map_navigate_cached))
                    }
                }
            }
            EmergencyMapUi.Empty -> ListEmpty(
                title = stringResource(R.string.map_empty),
                body = stringResource(R.string.map_empty_body),
                actionLabel = stringResource(R.string.list_retry),
                onAction = onRefresh,
            )
            is EmergencyMapUi.Ready -> MapReady(map)
        }
    }
}

@Composable
private fun MapReady(map: EmergencyMapUi.Ready) {
    PointSketch(points = map.points, modifier = Modifier.fillMaxWidth().height(220.dp))
    Spacer(modifier = Modifier.height(8.dp))
    val saved = cacheClock(map.fetchedAt)
    Text(
        text = if (map.fromCache) {
            stringResource(R.string.map_cached_at, saved)
        } else {
            stringResource(R.string.map_live, saved)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(12.dp))
    map.points.forEach { point ->
        val kind = kindLabel(point.kind)
        val line = stringResource(R.string.map_point, kind, point.name)
        Text(
            text = line,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .semantics { contentDescription = line },
        )
    }
}

@Composable
fun LocationSnippet(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    Text(text = stringResource(R.string.detail_map), style = MaterialTheme.typography.titleMedium, modifier = modifier)
    Spacer(modifier = Modifier.height(8.dp))
    PointSketch(
        points = listOf(
            EmergencyPointItem(
                id = "report",
                kind = POINT_WATER,
                name = "",
                latitude = latitude,
                longitude = longitude,
            ),
        ),
        modifier = Modifier.fillMaxWidth().height(120.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.detail_map_coords, latitude, longitude),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PointSketch(points: List<EmergencyPointItem>, modifier: Modifier = Modifier) {
    val water = Color(0xFF0B6E4F)
    val evacuation = Color(0xFF8C4A2F)
    Canvas(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        if (points.isEmpty()) {
            return@Canvas
        }
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val latSpan = (maxLat - minLat).takeIf { it > 0.0001 } ?: 0.02
        val lngSpan = (maxLng - minLng).takeIf { it > 0.0001 } ?: 0.02
        val pad = 28.dp.toPx()
        points.forEach { point ->
            val x = pad + ((point.longitude - minLng) / lngSpan).toFloat() * (size.width - pad * 2)
            val y = pad + ((maxLat - point.latitude) / latSpan).toFloat() * (size.height - pad * 2)
            val color = if (point.kind == POINT_EVACUATION) evacuation else water
            drawCircle(color = color, radius = 10.dp.toPx(), center = Offset(x, y))
        }
    }
    if (points.any { it.kind == POINT_WATER } && points.any { it.kind == POINT_EVACUATION }) {
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(color = water, label = stringResource(R.string.map_kind_water))
            LegendDot(color = evacuation, label = stringResource(R.string.map_kind_evacuation))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    POINT_EVACUATION -> stringResource(R.string.map_kind_evacuation)
    else -> stringResource(R.string.map_kind_water)
}

private fun cacheClock(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()))
}
