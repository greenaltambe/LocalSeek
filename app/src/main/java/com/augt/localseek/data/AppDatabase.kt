package com.augt.localseek.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver


@SuppressLint("RestrictedApi")
@Database(
    // List all of @Entity classes here.
    entities = [DocumentEntity::class, DocumentFts::class],
    version = 8, // Increment to 8 to clear old data and force re-index
    exportSchema = false
)
@TypeConverters(VectorConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hybrid_search.db"
                )
                // Use bundled SQLite to ensure FTS5 and BM25 support on all devices
                .setDriver(BundledSQLiteDriver())
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        suspend fun seedTestData(context: Context) {
            // No-op: We now rely on FileIndexer to crawl real storage files.
        }
    }
}
