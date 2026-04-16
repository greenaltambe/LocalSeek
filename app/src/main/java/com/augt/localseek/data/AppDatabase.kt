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
    version = 6, // Increment version to 6 to force a re-index of files
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
            val dao = getInstance(context).documentDao()
            if (dao.getDocumentCount() > 0) return

            // Temporarily spin up the AI model to encode our test data
            val encoder = com.augt.localseek.ml.DenseEncoder(context)

            val testDocs = listOf(
                DocumentEntity(
                    filePath = "/test/android_guide.txt",
                    title = "Android development guide",
                    body = "Android is a mobile operating system developed by Google...",
                    fileType = "txt", modifiedAt = System.currentTimeMillis(), sizeBytes = 1024,
                    embedding = encoder.encode("Android is a mobile operating system developed by Google...")
                ),
                DocumentEntity(
                    filePath = "/test/ml_basics.txt",
                    title = "Machine learning basics",
                    body = "Machine learning is a branch of artificial intelligence...",
                    fileType = "txt", modifiedAt = System.currentTimeMillis(), sizeBytes = 2048,
                    embedding = encoder.encode("Machine learning is a branch of artificial intelligence...")
                ),
                DocumentEntity(
                    filePath = "/test/kotlin_coroutines.md",
                    title = "Kotlin coroutines guide",
                    body = "Coroutines are a Kotlin feature that converts async callbacks...",
                    fileType = "md", modifiedAt = System.currentTimeMillis(), sizeBytes = 512,
                    embedding = encoder.encode("Coroutines are a Kotlin feature that converts async callbacks...")
                ),
                DocumentEntity(
                    filePath = "/test/retrieval_ir.txt",
                    title = "Information retrieval systems",
                    body = "Information retrieval is the process of obtaining relevant documents...",
                    fileType = "txt", modifiedAt = System.currentTimeMillis(), sizeBytes = 3000,
                    embedding = encoder.encode("Information retrieval is the process of obtaining relevant documents...")
                )
            )

            testDocs.forEach { dao.insert(it) }

            // Close the AI model
            encoder.close()
        }
    }
}
