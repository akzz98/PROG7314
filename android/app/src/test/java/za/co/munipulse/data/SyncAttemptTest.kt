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
    fun leavesAckAndConflictRowsAloneOnRetry() {
        val acked = row(status = SyncStatus.ACK, retryCount = 3)
        val conflict = row(status = SyncStatus.CONFLICT, retryCount = 2)

        assertEquals(acked, SyncAttempt.retry(acked, online = false))
        assertEquals(conflict, SyncAttempt.retry(conflict, online = true))
    }

    @Test
    fun movesPendingToAckOnASuccessfulPush() {
        val updated = SyncAttempt.apply(row(), "ack", null)

        assertEquals(SyncStatus.ACK, updated.status)
        assertEquals(null, updated.lastError)
        assertEquals(0, updated.retryCount)
    }

    @Test
    fun movesPendingToAckWhenTheMutationWasAlreadyStored() {
        val updated = SyncAttempt.apply(row(), "conflict", "DUPLICATE_MUTATION")

        assertEquals(SyncStatus.ACK, updated.status)
        assertEquals(null, updated.lastError)
    }

    @Test
    fun movesPendingToConflictForAResolvedEdit() {
        val updated = SyncAttempt.apply(row(), "conflict", SyncAttempt.RESOLVED)

        assertEquals(SyncStatus.CONFLICT, updated.status)
        assertEquals(SyncAttempt.RESOLVED, updated.lastError)
        assertEquals(SyncStatus.PENDING, row().status)
    }

    @Test
    fun leavesAnUnknownResultPending() {
        val pending = row(retryCount = 4)
        val updated = SyncAttempt.apply(pending, "unknown", null)

        assertEquals(pending, updated)
        assertEquals(SyncStatus.PENDING, updated.status)
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
