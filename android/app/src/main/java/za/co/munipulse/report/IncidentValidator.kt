package za.co.munipulse.report

import za.co.munipulse.R
import za.co.munipulse.auth.WardCatalog

data class IncidentDraft(
    val category: String,
    val description: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val wardCode: String,
    val photoCount: Int,
)

// Same rules as the API ValidateCreate method. Nothing is sent from here.
object IncidentValidator {
    fun validate(draft: IncidentDraft): Map<String, Int> {
        val errors = linkedMapOf<String, Int>()
        if (!IncidentCategories.isKnown(draft.category)) {
            errors["category"] = R.string.validation_category
        }
        val description = draft.description.trim()
        if (description.length !in DESCRIPTION_MIN..DESCRIPTION_MAX) {
            errors["description"] = R.string.validation_description
        }
        val latitude = draft.latitude
        if (latitude == null || latitude !in -90.0..90.0) {
            errors["latitude"] = R.string.validation_latitude
        }
        val longitude = draft.longitude
        if (longitude == null || longitude !in -180.0..180.0) {
            errors["longitude"] = R.string.validation_longitude
        }
        val accuracy = draft.accuracyMeters
        if (accuracy != null && (accuracy < 0f || accuracy > ACCURACY_MAX)) {
            errors["accuracyMeters"] = R.string.validation_accuracy
        }
        if (!WardCatalog.isKnown(draft.wardCode)) {
            errors["wardCode"] = R.string.validation_ward
        }
        if (draft.photoCount > IncidentCategories.MAX_PHOTOS) {
            errors["photoIds"] = R.string.validation_photos
        }
        return errors
    }

    private const val DESCRIPTION_MIN = 10
    private const val DESCRIPTION_MAX = 1000
    private const val ACCURACY_MAX = 10_000f
}
