package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface DocumentDao {

    /**
     * Inserts a new document. If a document with the same `filePath` already
     * exists, it will be replaced. This is crucial for re-indexing updated files.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    /**
     * Performs a full-text search using BM25w ranking.
     *
     * How this query works:
     * 1. `WHERE documents_fts MATCH :query`: This uses the FTS index to quickly
     *    find all rows that match the user's query terms.
     * 2. `bm25(documents_fts, 1.2, 0.75) AS score`: For each matching row, SQLite calculates
     *    the BM25 relevance score. NOTE: The most relevant documents have the
     *    most *negative* scores (e.g., -10.0 is better than -2.0).
     * 3. `ORDER BY score ASC`: We order by the score in ascending order to get the
     *    most relevant (most negative) results first.
     * 4. `JOIN documents d ON documents_fts.rowid = d.id`: We link the FTS results
     *    back to our main `documents` table to retrieve all the metadata.
     * 5. `LIMIT 50`: We only care about the top 50 candidates for performance.
     */
    @Query("""
        SELECT d.id, d.filePath, d.title, d.body, d.fileType, d.modifiedAt,
               bm25(documents_fts, 1.2, 0.75) AS score
        FROM documents_fts
        JOIN documents d ON documents_fts.rowid = d.id
        WHERE documents_fts MATCH :query
        ORDER BY score ASC
        LIMIT 50
    """)
    suspend fun searchBm25(query: String): List<BM25Result>

    /**
     * Used by the file indexer (Phase 2) to check if a file has been modified
     * since the last time it was indexed.
     */
    @Query("SELECT modifiedAt FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getModifiedAt(path: String): Long?

    /**
     * Called by the FileObserver (Phase 2) when it detects that a file has been deleted.
     */
    @Query("DELETE FROM documents WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    /**
     * Used to check if we need to seed the database with initial test data.
     */
    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getDocumentCount(): Int
}
