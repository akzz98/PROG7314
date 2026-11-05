package za.co.munipulse.data

import com.google.gson.Gson
import za.co.munipulse.report.IncidentDraft

data class QueuedIncidentPayload(
    val category: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val wardCode: String,
    val clientMutationId: String,
)

// Builds the Room row for a report that cannot leave the phone yet.
object QueuedIncident {
    const val MUTATION_TYPE = "createIncident"

    private val gson = Gson()

    fun operation(
        draft: IncidentDraft,
        clientMutationId: String,
        photoPaths: List<String>,
        createdAt: Long,
    ): PendingSyncOperation? {
        val payload = QueuedIncidentPayload(
            category = draft.category,
            description = draft.description.trim(),
            latitude = draft.latitude ?: return null,
            longitude = draft.longitude ?: return null,
            accuracyMeters = draft.accuracyMeters,
            wardCode = draft.wardCode,
            clientMutationId = clientMutationId,
        )
        return PendingSyncOperation(
            id = clientMutationId,
            mutationType = MUTATION_TYPE,
            payloadJson = gson.toJson(payload),
            photoLocalUri = photoColumn(photoPaths),
            status = SyncStatus.PENDING,
            retryCount = 0,
            lastError = null,
            createdAt = createdAt,
        )
    }

    fun photoColumn(photoPaths: List<String>): String? =
        if (photoPaths.isEmpty()) null else gson.toJson(photoPaths)

    fun readPhotoPaths(column: String?): List<String> {
        if (column.isNullOrBlank()) {
            return emptyList()
        }
        return gson.fromJson(column, Array<String>::class.java)?.toList()
            ?: throw IllegalArgumentException("photos")
    }

    fun readPayload(json: String): QueuedIncidentPayload =
        gson.fromJson(json, QueuedIncidentPayload::class.java)
}
