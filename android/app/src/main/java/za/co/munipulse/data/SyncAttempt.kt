package za.co.munipulse.data

// A local retry. It does not contact the API.
object SyncAttempt {
    const val OFFLINE = "offline"
    const val WAITING = "waiting"
    const val RESOLVED = "RESOLVED"

    fun apply(row: PendingSyncOperation, result: String, code: String?): PendingSyncOperation {
        if (code == "DUPLICATE_MUTATION") {
            return row.copy(status = SyncStatus.ACK, lastError = null)
        }
        return when (result) {
            "ack" -> row.copy(status = SyncStatus.ACK, lastError = null)
            "conflict" -> row.copy(status = SyncStatus.CONFLICT, lastError = code ?: "conflict")
            else -> row
        }
    }

    fun retry(row: PendingSyncOperation, online: Boolean): PendingSyncOperation {
        if (row.status != SyncStatus.PENDING) {
            return row
        }
        return row.copy(
            retryCount = row.retryCount + 1,
            lastError = if (online) WAITING else OFFLINE,
        )
    }
}
