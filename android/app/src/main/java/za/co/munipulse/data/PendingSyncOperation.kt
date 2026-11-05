package za.co.munipulse.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Part 1 Room row. Later sync items fill it. This item only creates the table.
@Entity(tableName = "pending_sync_operations")
data class PendingSyncOperation(
    @PrimaryKey val id: String,
    val mutationType: String,
    val payloadJson: String,
    val photoLocalUri: String?,
    val status: String,
    val retryCount: Int,
    val lastError: String?,
    val createdAt: Long,
)

object SyncStatus {
    const val PENDING = "pending"
    const val ACK = "ack"
    const val CONFLICT = "conflict"
}
