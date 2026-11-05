package za.co.munipulse.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncAttemptTest {
    @Test
    fun recordsAnOfflineRetry() {
        val updated = SyncAttempt.retry(row(retryCount = 1), online = false)

        assertEquals(2, updated.retryCount)
        assertEquals(SyncAttempt.OFFLINE, updated.lastError)
        assertEquals(SyncStatus.PENDING, updated.status)
    }

    @Test
    fun recordsAWaitingRetryWhenThePhoneIsOnline() {
        val updated = SyncAttempt.retry(row(retryCount = 0), online = true)

        assertEquals(1, updated.retryCount)
        assertEquals(SyncAttempt.WAITING, updated.lastError)
    }

    @Test
    fun leavesAFinishedRowAlone() {
        val original = row(status = SyncStatus.ACK, retryCount = 3)
        val updated = SyncAttempt.retry(original, online = false)

        assertEquals(original, updated)
    }

    @Test
    fun marksAnAckAndAConflict() {
        val pending = row()
        val acked = SyncAttempt.apply(pending, "ack", null)
        val conflict = SyncAttempt.apply(pending, "conflict", "DUPLICATE_MUTATION")

        assertEquals(SyncStatus.ACK, acked.status)
        assertEquals(null, acked.lastError)
        assertEquals(SyncStatus.CONFLICT, conflict.status)
        assertEquals("DUPLICATE_MUTATION", conflict.lastError)
    }

    private fun row(
        status: String = SyncStatus.PENDING,
        retryCount: Int = 0,
    ) = PendingSyncOperation(
        id = "mutation-1",
        mutationType = QueuedIncident.MUTATION_TYPE,
        payloadJson = "{}",
        photoLocalUri = null,
        status = status,
        retryCount = retryCount,
        lastError = null,
        createdAt = 1L,
    )
}
