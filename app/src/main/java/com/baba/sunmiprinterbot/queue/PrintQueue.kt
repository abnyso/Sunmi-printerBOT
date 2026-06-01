package com.baba.sunmiprinterbot.queue

import androidx.room.*

@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // "text", "image", "agenda"
    val content: String,       // testo o path immagine o data ISO
    val createdAt: Long = System.currentTimeMillis(),
    val printed: Boolean = false,
    val retryCount: Int = 0
)

@Dao
interface PrintJobDao {
    @Insert
    suspend fun insert(job: PrintJob): Long

    @Query("SELECT * FROM print_jobs WHERE printed = 0 ORDER BY createdAt ASC")
    suspend fun getPending(): List<PrintJob>

    @Query("UPDATE print_jobs SET printed = 1 WHERE id = :id")
    suspend fun markPrinted(id: Long)

    @Query("UPDATE print_jobs SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM print_jobs WHERE printed = 1 AND createdAt < :before")
    suspend fun cleanOld(before: Long)
}

@Database(entities = [PrintJob::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printJobDao(): PrintJobDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "print_queue.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
