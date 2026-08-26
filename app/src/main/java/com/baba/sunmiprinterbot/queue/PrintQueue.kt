package com.baba.sunmiprinterbot.queue

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val MAX_RETRIES = 5

@Entity(tableName = "print_jobs")
data class PrintJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // "text", "image", "agenda", "qr", "test"
    val content: String,       // testo, path immagine o data ISO
    val createdAt: Long = System.currentTimeMillis(),
    val printed: Boolean = false,
    val failed: Boolean = false,
    val retryCount: Int = 0
)

@Dao
interface PrintJobDao {
    @Insert
    suspend fun insert(job: PrintJob): Long

    // Only jobs that are neither printed nor permanently failed.
    @Query("SELECT * FROM print_jobs WHERE printed = 0 AND failed = 0 ORDER BY createdAt ASC")
    suspend fun getPending(): List<PrintJob>

    @Query("SELECT * FROM print_jobs WHERE failed = 1 ORDER BY createdAt ASC")
    suspend fun getFailed(): List<PrintJob>

    @Query("UPDATE print_jobs SET printed = 1 WHERE id = :id")
    suspend fun markPrinted(id: Long)

    @Query("UPDATE print_jobs SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    @Query("UPDATE print_jobs SET failed = 1 WHERE id = :id")
    suspend fun markFailed(id: Long)

    // Requeue every failed job for another round of attempts.
    @Query("UPDATE print_jobs SET failed = 0, retryCount = 0 WHERE failed = 1")
    suspend fun retryFailed(): Int

    // Drop everything not yet printed (pending + failed). Returns rows removed.
    @Query("DELETE FROM print_jobs WHERE printed = 0")
    suspend fun clearUnprinted(): Int

    @Query("DELETE FROM print_jobs WHERE printed = 1 AND createdAt < :before")
    suspend fun cleanOld(before: Long)
}

@Database(entities = [PrintJob::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printJobDao(): PrintJobDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE print_jobs ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "print_queue.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
