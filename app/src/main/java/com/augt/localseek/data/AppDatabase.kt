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
    entities = [
        DocumentEntity::class, DocumentFts::class, DocumentChunk::class, ChunkFts::class,
        AppEntity::class, AppFts::class, ContactEntity::class, ContactFts::class,
        BenchmarkRunEntity::class
    ],
    version = 14,
    exportSchema = false
)
@TypeConverters(VectorConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun appDao(): AppDao
    abstract fun contactDao(): ContactDao
    abstract fun benchmarkRunDao(): BenchmarkRunDao

    private object Migration1To2 : Migration(1, 2) {
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
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(parentFileId) REFERENCES documents(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts
                USING fts5(text, content=document_chunks, content_rowid=id)
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS chunks_fts_insert
                AFTER INSERT ON document_chunks
                BEGIN
                    INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
                END
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS chunks_fts_delete
                AFTER DELETE ON document_chunks
                BEGIN
                    INSERT INTO chunks_fts(chunks_fts, rowid, text)
                    VALUES('delete', old.id, old.text);
                END
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS chunks_fts_update
                AFTER UPDATE ON document_chunks
                BEGIN
                    INSERT INTO chunks_fts(chunks_fts, rowid, text)
                    VALUES('delete', old.id, old.text);
                    INSERT INTO chunks_fts(rowid, text) VALUES (new.id, new.text);
                END
                """.trimIndent()
            )

            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_document_chunks_parentFileId
                ON document_chunks(parentFileId)
                """.trimIndent()
            )
        }
    }

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

    private object Migration12To13 : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // Apps table
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS apps (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    packageName TEXT NOT NULL,
                    appName TEXT NOT NULL,
                    textRepresentation TEXT NOT NULL,
                    embedding BLOB,
                    lastIndexedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS apps_fts
                USING fts5(textRepresentation, content=apps, content_rowid=id, tokenize='unicode61')
                """.trimIndent()
            )
            
            // Triggers for apps_fts
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS apps_fts_insert AFTER INSERT ON apps BEGIN INSERT INTO apps_fts(rowid, textRepresentation) VALUES (new.id, new.textRepresentation); END")
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS apps_fts_delete AFTER DELETE ON apps BEGIN INSERT INTO apps_fts(apps_fts, rowid, textRepresentation) VALUES('delete', old.id, old.textRepresentation); END")
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS apps_fts_update AFTER UPDATE ON apps BEGIN INSERT INTO apps_fts(apps_fts, rowid, textRepresentation) VALUES('delete', old.id, old.textRepresentation); INSERT INTO apps_fts(rowid, textRepresentation) VALUES (new.id, new.textRepresentation); END")

            // Contacts table
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    contactId TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    textRepresentation TEXT NOT NULL,
                    embedding BLOB,
                    lastIndexedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS contacts_fts
                USING fts5(textRepresentation, content=contacts, content_rowid=id, tokenize='unicode61')
                """.trimIndent()
            )
            
            // Triggers for contacts_fts
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS contacts_fts_insert AFTER INSERT ON contacts BEGIN INSERT INTO contacts_fts(rowid, textRepresentation) VALUES (new.id, new.textRepresentation); END")
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS contacts_fts_delete AFTER DELETE ON contacts BEGIN INSERT INTO contacts_fts(contacts_fts, rowid, textRepresentation) VALUES('delete', old.id, old.textRepresentation); END")
            connection.execSQL("CREATE TRIGGER IF NOT EXISTS contacts_fts_update AFTER UPDATE ON contacts BEGIN INSERT INTO contacts_fts(contacts_fts, rowid, textRepresentation) VALUES('delete', old.id, old.textRepresentation); INSERT INTO contacts_fts(rowid, textRepresentation) VALUES (new.id, new.textRepresentation); END")
        }
    }

    private object Migration13To14 : Migration(13, 14) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS benchmark_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    runSessionId TEXT NOT NULL,
                    queryId TEXT NOT NULL,
                    queryText TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    deviceModel TEXT NOT NULL,
                    androidVersion TEXT NOT NULL,
                    backend TEXT NOT NULL,
                    corpusSizeChunks INTEGER NOT NULL,
                    corpusSizeApps INTEGER NOT NULL,
                    corpusSizeContacts INTEGER NOT NULL,
                    latencyBm25Ms INTEGER NOT NULL,
                    latencyDenseMs INTEGER NOT NULL,
                    latencyFusionMs INTEGER NOT NULL,
                    latencyRerankMs INTEGER,
                    latencyTotalMs INTEGER NOT NULL,
                    memoryMbPeak REAL NOT NULL,
                    batteryPctBefore INTEGER,
                    batteryPctAfter INTEGER,
                    resultIdsJson TEXT NOT NULL,
                    resultScoresJson TEXT NOT NULL,
                    resultEntityTypesJson TEXT NOT NULL
                )
                """.trimIndent()
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
                .addMigrations(Migration1To2, Migration10To11, Migration11To12, Migration12To13, Migration13To14)
                // Temporary dev safety valve for unsupported legacy version hops.
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
