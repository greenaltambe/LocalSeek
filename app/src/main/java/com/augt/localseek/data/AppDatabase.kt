package com.augt.localseek.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.TypeConverters
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL


@SuppressLint("RestrictedApi")
@Database(
    // List all of @Entity classes here.
    entities = [DocumentEntity::class, DocumentFts::class, DocumentChunk::class, ChunkFts::class],
    version = 12,
    exportSchema = false
)
@TypeConverters(VectorConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao

    private object Migration10To11 : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS document_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    parentFileId INTEGER NOT NULL,
                    chunkIndex INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    startOffset INTEGER NOT NULL,
                    endOffset INTEGER NOT NULL,
                    embedding BLOB,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts
                USING fts5(text, content=`document_chunks`, tokenize='unicode61')
                """.trimIndent()
            )

            // Backfill one chunk per legacy document body to preserve searchable data.
            connection.execSQL(
                """
                INSERT INTO document_chunks (parentFileId, chunkIndex, text, startOffset, endOffset, createdAt)
                SELECT id, 0, body, 0, length(body), CAST(strftime('%s','now') AS INTEGER) * 1000
                FROM documents
                WHERE body IS NOT NULL AND length(trim(body)) > 0
                """.trimIndent()
            )

            connection.execSQL(
                """
                INSERT INTO chunks_fts(rowid, text)
                SELECT id, text FROM document_chunks
                """.trimIndent()
            )
        }
    }

    private object Migration11To12 : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_document_chunks_parentFileId ON document_chunks(parentFileId)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_document_chunks_embedding ON document_chunks(embedding)"
            )
        }
    }

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
                .addMigrations(Migration10To11, Migration11To12)
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
