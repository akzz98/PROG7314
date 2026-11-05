package za.co.munipulse.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Dao
interface PendingSyncDao {
    @Query("SELECT * FROM pending_sync_operations ORDER BY createdAt ASC")
    suspend fun list(): List<PendingSyncOperation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: PendingSyncOperation)
}

@Dao
interface LocalSettingsDao {
    @Query("SELECT * FROM local_settings WHERE id = :id")
    suspend fun read(id: Int = LocalSettings.ROW_ID): LocalSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: LocalSettings)
}

@Database(
    entities = [PendingSyncOperation::class, LocalSettings::class],
    version = 1,
    exportSchema = false,
)
abstract class MuniPulseDatabase : RoomDatabase() {
    abstract fun pendingSync(): PendingSyncDao

    abstract fun localSettings(): LocalSettingsDao

    companion object {
        @Volatile
        private var instance: MuniPulseDatabase? = null

        fun get(context: Context): MuniPulseDatabase {
            val current = instance
            if (current != null) {
                return current
            }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuniPulseDatabase::class.java,
                    "munipulse.db",
                ).build().also { instance = it }
            }
        }
    }
}
