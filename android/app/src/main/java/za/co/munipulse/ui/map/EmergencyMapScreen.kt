package za.co.munipulse.ui.map

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.cos
import kotlin.math.sqrt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.ui.theme.PulseTeal
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
                    val cachedDescription = stringResource(R.string.map_navigate_cached_description)
                    Button(
                        onClick = onNavigateCached,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = cachedDescription
                            role = Role.Button
                        },
                    ) {
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
    Text(text = stringResource(R.string.map_how), style = MaterialTheme.typography.bodyLarge)
    val apart = apartKm(map.points)
    if (apart != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.map_apart, apart), style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(modifier = Modifier.height(12.dp))
    PointSketch(points = map.points, modifier = Modifier.fillMaxWidth().height(280.dp))
    Spacer(modifier = Modifier.height(12.dp))
    map.points.forEach { point ->
        val line = if (point.kind == POINT_EVACUATION) {
            stringResource(R.string.map_dot_evacuation, point.name)
        } else {
            stringResource(R.string.map_dot_water, point.name)
        }
        Text(
            text = line,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .semantics { contentDescription = line },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    val saved = cacheClock(map.fetchedAt)
    Text(
        text = if (map.fromCache) {
            stringResource(R.string.map_cached_at, saved)
        } else {
            stringResource(R.string.map_live, saved)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    val water = PulseTeal
    val evacuation = MaterialTheme.colorScheme.onSurface
    val canvasDescription = stringResource(R.string.map_canvas_description)
    val north = stringResource(R.string.map_north)
    val scaleLabel = stringResource(R.string.map_scale)
    val shape = RoundedCornerShape(16.dp)
    val plots = plot(points)
    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .semantics { contentDescription = canvasDescription },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val span = plotSpanMetres(points).toFloat().coerceAtLeast(1f)
            val bar = (1000f / span).coerceIn(0.12f, 0.42f) * size.width
            val left = 16.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            drawLine(
                color = evacuation,
                start = Offset(left, bottom),
                end = Offset(left + bar, bottom),
                strokeWidth = 3.dp.toPx(),
            )
        }
        Text(
            text = "↑ $north",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        Text(
            text = scaleLabel,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 22.dp),
        )
        plots.forEach { placed ->
            val label = if (placed.point.kind == POINT_EVACUATION) {
                stringResource(R.string.map_kind_evacuation)
            } else {
                stringResource(R.string.map_kind_water)
            }
            val color = if (placed.point.kind == POINT_EVACUATION) evacuation else water
            Column(
                modifier = Modifier
                    .offset(x = maxWidth * placed.x - 56.dp, y = maxHeight * placed.y - 10.dp)
                    .width(112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(modifier = Modifier.size(18.dp)) {
                    drawCircle(color = color)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class PlacedPoint(val point: EmergencyPointItem, val x: Float, val y: Float)

private fun plot(points: List<EmergencyPointItem>): List<PlacedPoint> {
    if (points.isEmpty()) {
        return emptyList()
    }
    val midLat = points.map { it.latitude }.average()
    val midLng = points.map { it.longitude }.average()
    val span = plotSpanMetres(points)
    return points.map { point ->
        val east = eastMetres(point.longitude, midLng, midLat)
        val north = northMetres(point.latitude, midLat)
        PlacedPoint(
            point = point,
            x = (0.5 + east / span).toFloat().coerceIn(0.22f, 0.78f),
            y = (0.5 - north / span).toFloat().coerceIn(0.24f, 0.72f),
        )
    }
}

private fun plotSpanMetres(points: List<EmergencyPointItem>): Double {
    if (points.isEmpty()) {
        return 2_000.0
    }
    val midLat = points.map { it.latitude }.average()
    val midLng = points.map { it.longitude }.average()
    val farthest = points.maxOf { point ->
        val east = eastMetres(point.longitude, midLng, midLat)
        val north = northMetres(point.latitude, midLat)
        sqrt(east * east + north * north)
    }
    return farthest.coerceAtLeast(700.0) * 2.8
}

private fun apartKm(points: List<EmergencyPointItem>): Double? {
    if (points.size < 2) {
        return null
    }
    var farthest = 0.0
    for (left in points.indices) {
        for (right in left + 1 until points.size) {
            val east = eastMetres(points[left].longitude, points[right].longitude, points[left].latitude)
            val north = northMetres(points[left].latitude, points[right].latitude)
            val km = sqrt(east * east + north * north) / 1000.0
            if (km > farthest) {
                farthest = km
            }
        }
    }
    return farthest
}

private fun northMetres(latitude: Double, originLatitude: Double): Double =
    (latitude - originLatitude) * 110_540.0

private fun eastMetres(longitude: Double, originLongitude: Double, latitude: Double): Double =
    (longitude - originLongitude) * 111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2)

private fun cacheClock(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()))
}
