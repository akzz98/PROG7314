package za.co.munipulse.report

import za.co.munipulse.R

data class IncidentCategory(val code: String, val label: Int)

// Same codes as the API IncidentCategories catalog.
object IncidentCategories {
    const val DEFAULT = "Pothole"
    const val MAX_PHOTOS = 3

    val all: List<IncidentCategory> = listOf(
        IncidentCategory("Pothole", R.string.category_pothole),
        IncidentCategory("WaterLeak", R.string.category_water_leak),
        IncidentCategory("IllegalDumping", R.string.category_illegal_dumping),
        IncidentCategory("Streetlight", R.string.category_streetlight),
        IncidentCategory("Sewage", R.string.category_sewage),
        IncidentCategory("Other", R.string.category_other),
    )

    fun isKnown(code: String): Boolean = all.any { it.code == code }
}
