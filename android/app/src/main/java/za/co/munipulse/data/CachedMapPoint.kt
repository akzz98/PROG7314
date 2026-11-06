package za.co.munipulse.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(tableName = "cached_map_points")
data class CachedMapPoint(
    @PrimaryKey val id: String,
    val wardCode: String,
    val kind: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long,
)

@Dao
abstract class CachedMapPointDao {
    @Query("SELECT * FROM cached_map_points WHERE wardCode = :wardCode ORDER BY kind ASC, name ASC")
    abstract suspend fun list(wardCode: String): List<CachedMapPoint>

    @Query("DELETE FROM cached_map_points WHERE wardCode = :wardCode")
    abstract suspend fun deleteWard(wardCode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(points: List<CachedMapPoint>)

    @Transaction
    open suspend fun replaceWard(wardCode: String, points: List<CachedMapPoint>) {
        deleteWard(wardCode)
        if (points.isNotEmpty()) {
            insertAll(points)
        }
    }
}
