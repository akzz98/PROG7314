package za.co.munipulse.ui.incidents

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface MyIncidentsUi {
    data object Idle : MyIncidentsUi
    data object Loading : MyIncidentsUi
    data class Ready(val items: List<MyIncidentItem>) : MyIncidentsUi
    data class Failed(val message: String) : MyIncidentsUi
}

data class MyIncidentItem(
    val id: String,
    val category: String,
    val status: String,
    val createdAt: String,
    val upvoteCount: Int = 0,
    val viewerHasUpvoted: Boolean = false,
)

sealed interface IncidentDetailUi {
    data object Idle : IncidentDetailUi
    data object Loading : IncidentDetailUi
    data class Ready(val item: IncidentDetailItem) : IncidentDetailUi
    data class Failed(val message: String) : IncidentDetailUi
}

data class IncidentDetailItem(
    val id: String,
    val category: String,
    val place: String,
    val description: String,
    val status: String,
    val upvoteCount: Int,
    val viewerHasUpvoted: Boolean,
    val upvoteNote: String = "",
    val photoIds: List<String>,
    val timeline: List<TimelineLine>,
)

data class TimelineLine(
    val at: String,
    val type: String,
    val note: String,
)

object IncidentTime {
    fun whenLabel(value: String): String {
        val instant = parse(value) ?: return ""
        val zone = ZoneId.systemDefault()
        val day = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            day == today -> "today"
            day == today.minusDays(1) -> "yesterday"
            day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }

    fun clockLabel(value: String): String {
        val instant = parse(value) ?: return ""
        val zone = ZoneId.systemDefault()
        val local = instant.atZone(zone)
        val time = local.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
        val today = LocalDate.now(zone)
        return if (local.toLocalDate() == today) {
            time
        } else {
            "${local.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} $time"
        }
    }

    private fun parse(value: String): Instant? {
        val text = value.trim()
        if (text.isEmpty()) {
            return null
        }
        runCatching { return Instant.parse(text) }
        runCatching { return OffsetDateTime.parse(text).toInstant() }
        runCatching { return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }
        return null
    }
}
