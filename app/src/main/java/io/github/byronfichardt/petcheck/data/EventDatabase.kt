package io.github.byronfichardt.petcheck.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/** One detected sound. Only this metadata is kept: audio is never stored or uploaded. */
@Entity(tableName = "events", indices = [Index("startedAt"), Index("synced")])
data class SoundEvent(
    @PrimaryKey val id: String,
    val deviceId: String,
    val deviceName: String,
    /** Epoch milliseconds. */
    val startedAt: Long,
    val durationMs: Long,
    val type: SoundType,
    /** Loudest level during the event, in dBFS. */
    val peakDb: Float,
    /** Classifier confidence for [type], 0 for [SoundType.OTHER]. */
    val confidence: Float,
    /** True once uploaded, or if the event came from another device. */
    val synced: Boolean = false,
)

class Converters {
    @TypeConverter
    fun typeToKey(type: SoundType): String = type.key

    @TypeConverter
    fun keyToType(key: String): SoundType = SoundType.fromKey(key)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE startedAt >= :from AND startedAt < :to ORDER BY startedAt DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<SoundEvent>>

    @Query("SELECT * FROM events ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SoundEvent>>

    @Query("SELECT * FROM events WHERE synced = 0 AND deviceId = :deviceId ORDER BY startedAt LIMIT :limit")
    fun observeUnsynced(deviceId: String, limit: Int): Flow<List<SoundEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(events: List<SoundEvent>)

    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE events SET synced = 0 WHERE deviceId = :deviceId")
    suspend fun markAllUnsynced(deviceId: String)

    @Query("DELETE FROM events WHERE deviceId != :deviceId")
    suspend fun deleteOtherDevices(deviceId: String)

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}

@Database(entities = [SoundEvent::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class EventDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): EventDatabase =
            Room.databaseBuilder(context, EventDatabase::class.java, "petcheck.db").build()
    }
}
