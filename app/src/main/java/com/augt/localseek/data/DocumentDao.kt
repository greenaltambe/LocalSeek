package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface DocumentDao {

    /**
     * Inserts a new document.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    /**
     * Performs a full-text search using BM25w ranking.
     */
    @Query("""
        SELECT d.id, d.filePath, d.title, d.body, d.fileType, d.modifiedAt,
               bm25(documents_fts, 1.2, 0.75) AS score
        FROM documents_fts
        JOIN documents d ON documents_fts.rowid = d.id
        WHERE documents_fts MATCH :query
        ORDER BY score ASC
        LIMIT :limit
    """)
    suspend fun searchBm25(query: String, limit: Int): List<BM25Result>

    /**
     * Used by the file indexer to check if a file has been modified.
     */
    @Query("SELECT modifiedAt FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getModifiedAt(path: String): Long?

    /**
     * Deletes every chunk associated with that path.
     */
    @Query("DELETE FROM documents WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    /**
     * Used to check if we need to seed the database.
     */
    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getDocumentCount(): Int

    /**
     * Fetches all chunks that have an AI embedding generated.
     */
    @Query("SELECT id, filePath, title, body, fileType, modifiedAt, embedding FROM documents WHERE embedding IS NOT NULL")
    suspend fun getAllVectors(): List<VectorResult>
}
