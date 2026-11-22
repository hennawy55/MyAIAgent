package com.example.myaiagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fireAtMillis: Long,
    val message: String,
    val source: String,
    val deliveryMode: String,
    val status: String = "PENDING",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY fireAtMillis ASC")
    fun observeAll(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE status = 'PENDING' ORDER BY fireAtMillis ASC")
    suspend fun pending(): List<ReminderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ReminderEntity): Long
    @Query("UPDATE reminders SET status = 'DONE' WHERE id = :id")
    suspend fun markDone(id: Long)
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ReminderEntity?
}

@Database(entities = [ReminderEntity::class], version = 1, exportSchema = false)
abstract class ReminderDatabase : RoomDatabase() {
    abstract fun reminders(): ReminderDao
    companion object {
        @Volatile private var INSTANCE: ReminderDatabase? = null
        fun get(context: Context): ReminderDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReminderDatabase::class.java,
                    "my_ai_agent.db"
                ).build().also { INSTANCE = it }
            }
    }
}
