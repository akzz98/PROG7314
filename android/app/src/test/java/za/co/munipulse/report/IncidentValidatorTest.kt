package za.co.munipulse.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.munipulse.R

class IncidentValidatorTest {
    @Test
    fun acceptsACompleteDraft() {
        assertTrue(IncidentValidator.validate(draft()).isEmpty())
    }

    @Test
    fun acceptsDescriptionBoundsAndOptionalAccuracy() {
        assertTrue(IncidentValidator.validate(draft(description = "a".repeat(10))).isEmpty())
        assertTrue(IncidentValidator.validate(draft(description = "a".repeat(1000))).isEmpty())
        assertTrue(IncidentValidator.validate(draft(description = "  1234567890  ")).isEmpty())
        assertTrue(IncidentValidator.validate(draft(accuracyMeters = null)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(accuracyMeters = 0f)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(accuracyMeters = 10_000f)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(latitude = -90.0, longitude = -180.0)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(latitude = 90.0, longitude = 180.0)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(photoCount = 0)).isEmpty())
        assertTrue(IncidentValidator.validate(draft(photoCount = 3)).isEmpty())
    }

    @Test
    fun rejectsEachInvalidField() {
        assertEquals(R.string.validation_category, IncidentValidator.validate(draft(category = "Hole"))["category"])
        assertEquals(R.string.validation_description, IncidentValidator.validate(draft(description = "too short"))["description"])
        assertEquals(R.string.validation_description, IncidentValidator.validate(draft(description = "a".repeat(1001)))["description"])
        assertEquals(R.string.validation_description, IncidentValidator.validate(draft(description = "   "))["description"])
        assertEquals(R.string.validation_latitude, IncidentValidator.validate(draft(latitude = null))["latitude"])
        assertEquals(R.string.validation_latitude, IncidentValidator.validate(draft(latitude = 90.1))["latitude"])
        assertEquals(R.string.validation_latitude, IncidentValidator.validate(draft(latitude = Double.NaN))["latitude"])
        assertEquals(R.string.validation_longitude, IncidentValidator.validate(draft(longitude = null))["longitude"])
        assertEquals(R.string.validation_longitude, IncidentValidator.validate(draft(longitude = -180.1))["longitude"])
        assertEquals(R.string.validation_accuracy, IncidentValidator.validate(draft(accuracyMeters = -0.1f))["accuracyMeters"])
        assertEquals(R.string.validation_accuracy, IncidentValidator.validate(draft(accuracyMeters = 10_001f))["accuracyMeters"])
        assertEquals(R.string.validation_ward, IncidentValidator.validate(draft(wardCode = "JHB-99"))["wardCode"])
        assertEquals(R.string.validation_photos, IncidentValidator.validate(draft(photoCount = 4))["photoIds"])
    }

    @Test
    fun reportsEveryInvalidFieldTogether() {
        val errors = IncidentValidator.validate(
            draft(
                category = "",
                description = "",
                latitude = null,
                longitude = null,
                accuracyMeters = -1f,
                wardCode = "",
                photoCount = 9,
            ),
        )
        assertEquals(
            listOf("category", "description", "latitude", "longitude", "accuracyMeters", "wardCode", "photoIds"),
            errors.keys.toList(),
        )
    }

    private fun draft(
        category: String = "Pothole",
        description: String = "Deep pothole outside the clinic gate",
        latitude: Double? = -26.2041,
        longitude: Double? = 28.0473,
        accuracyMeters: Float? = 12.5f,
        wardCode: String = "JHB-23",
        photoCount: Int = 1,
    ) = IncidentDraft(category, description, latitude, longitude, accuracyMeters, wardCode, photoCount)
}