package za.co.munipulse.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row. Language and biometric stay in preferences for a fast read.
// This row mirrors them, and holds the last sync time for the sync centre.
@Entity(tableName = "local_settings")
data class LocalSettings(
    @PrimaryKey val id: Int = ROW_ID,
    val biometricEnabled: Boolean,
    val lastSyncAt: Long?,
    val language: String,
) {
    companion object {
        const val ROW_ID = 1
    }
}
