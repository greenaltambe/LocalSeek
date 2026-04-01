package com.augt.localseek.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    // List all of @Entity classes here.
    entities = [DocumentEntity::class, DocumentFts::class],
    version = 5, // Increment version to ensure clean state
    exportSchema = false
)
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

            val testDocs = listOf(
                DocumentEntity(
                    filePath = "/test/android_guide.txt",
                    title = "Android development guide",
                    body = "Android is a mobile operating system developed by Google. " +
                            "It uses the Linux kernel and is designed primarily for touchscreen devices. " +
                            "Android applications are written in Kotlin or Java using the Android SDK.",
                    fileType = "txt",
                    modifiedAt = System.currentTimeMillis(),
                    sizeBytes = 1024
                ),
                DocumentEntity(
                    filePath = "/test/ml_basics.txt",
                    title = "Machine learning basics",
                    body = "Machine learning is a branch of artificial intelligence that enables " +
                            "systems to learn from data without being explicitly programmed. " +
                            "Common algorithms include decision trees, neural networks, and support vector machines.",
                    fileType = "txt",
                    modifiedAt = System.currentTimeMillis(),
                    sizeBytes = 2048
                ),
                DocumentEntity(
                    filePath = "/test/kotlin_coroutines.md",
                    title = "Kotlin coroutines guide",
                    body = "Coroutines are a Kotlin feature that converts async callbacks into sequential code. " +
                            "viewModelScope is used in ViewModel classes to launch coroutines that are " +
                            "automatically cancelled when the ViewModel is cleared.",
                    fileType = "md",
                    modifiedAt = System.currentTimeMillis(),
                    sizeBytes = 512
                ),
                DocumentEntity(
                    filePath = "/test/retrieval_ir.txt",
                    title = "Information retrieval systems",
                    body = "Information retrieval is the process of obtaining relevant documents from a collection. " +
                            "BM25 is the standard ranking algorithm used in search engines like Elasticsearch and Solr. " +
                            "Dense retrieval uses neural embeddings to find semantically similar documents.",
                    fileType = "txt",
                    modifiedAt = System.currentTimeMillis(),
                    sizeBytes = 3000
                )
            )

            testDocs.forEach { dao.insert(it) }
            println("Inserted ${testDocs.size} docs")
        }
    }
}
