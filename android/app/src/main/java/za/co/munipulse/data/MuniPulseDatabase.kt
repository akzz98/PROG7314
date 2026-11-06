package za.co.munipulse.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    entities = [PendingSyncOperation::class, LocalSettings::class, CachedMapPoint::class],
    version = 2,
    exportSchema = false,
)
abstract class MuniPulseDatabase : RoomDatabase() {
    abstract fun pendingSync(): PendingSyncDao

    abstract fun localSettings(): LocalSettingsDao

    abstract fun cachedMapPoints(): CachedMapPointDao

    companion object {
        @Volatile
        private var instance: MuniPulseDatabase? = null

        fun get(context: Context): MuniPulseDatabase {
            val current = instance
            if (current != null) {
                return current
            }
            return synchronized(this) {
                instance ?:                 Room.databaseBuilder(
                    context.applicationContext,
                    MuniPulseDatabase::class.java,
                    "munipulse.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_map_points (
                id TEXT NOT NULL PRIMARY KEY,
                wardCode TEXT NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                fetchedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
