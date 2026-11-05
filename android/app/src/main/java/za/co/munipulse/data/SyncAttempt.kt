package za.co.munipulse.data

// A local retry. It does not contact the API.
object SyncAttempt {
    const val OFFLINE = "offline"
    const val WAITING = "waiting"

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
