package za.co.munipulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.co.munipulse.report.IncidentDraft

class QueuedIncidentTest {
    @Test
    fun storesTheDraftAsAPendingRow() {
        val operation = checkNotNull(
            QueuedIncident.operation(
                draft = draft(accuracyMeters = null),
                clientMutationId = "mutation-1",
                photoPaths = emptyList(),
                createdAt = 10L,
            ),
        )

        assertEquals("mutation-1", operation.id)
        assertEquals(QueuedIncident.MUTATION_TYPE, operation.mutationType)
        assertEquals(SyncStatus.PENDING, operation.status)
        assertEquals(0, operation.retryCount)
        assertNull(operation.lastError)
        assertNull(operation.photoLocalUri)
        assertEquals(10L, operation.createdAt)
        val payload = QueuedIncident.readPayload(operation.payloadJson)
        assertEquals("Pothole", payload.category)
        assertEquals("A deep hole", payload.description)
        assertNull(payload.accuracyMeters)
        assertEquals("mutation-1", payload.clientMutationId)
    }

    @Test
    fun keepsPhotoPathsAndQuotedText() {
        val operation = checkNotNull(
            QueuedIncident.operation(
                draft = draft(description = "Hole by \"Main\" road"),
                clientMutationId = "mutation-2",
                photoPaths = listOf("/queued/0.jpg", "/queued/1.jpg"),
                createdAt = 20L,
            ),
        )

        assertEquals("[\"/queued/0.jpg\",\"/queued/1.jpg\"]", operation.photoLocalUri)
        val payload = QueuedIncident.readPayload(operation.payloadJson)
        assertEquals("Hole by \"Main\" road", payload.description)
        assertTrue(payload.latitude in -90.0..90.0)
    }

    private fun draft(
        description: String = "A deep hole",
        accuracyMeters: Float? = 8f,
    ) = IncidentDraft(
        category = "Pothole",
        description = description,
        latitude = -26.2,
        longitude = 28.04,
        accuracyMeters = accuracyMeters,
        wardCode = "JHB-23",
        photoCount = 0,
    )
}
