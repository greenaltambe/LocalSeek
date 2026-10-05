package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface DocumentDao {

    /**
     * Inserts or updates a document.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: DocumentEntity): Long

    /**
     * Used by the file indexer to check if a file has been modified.
     */
    @Query("SELECT modifiedAt FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getModifiedAt(path: String): Long?

    @Query("SELECT id FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getDocumentIdByPath(path: String): Long?

    @Query("SELECT * FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getDocumentByPath(path: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE stableKey = :stableKey LIMIT 1")
    suspend fun getByStableKey(stableKey: String): DocumentEntity?

    @Query("SELECT stableKey FROM documents")
    suspend fun getAllStableKeys(): List<String>

    @Query("DELETE FROM documents WHERE stableKey IN (:keys)")
    suspend fun deleteByStableKeys(keys: List<String>)

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT id, filePath FROM documents")
    suspend fun getAllDocumentPaths(): List<DocumentPath>

    /**
     * Deletes a document by file path.
     */
    @Query("DELETE FROM documents WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    /**
     * Used to check document count.
     */
    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getDocumentCount(): Int

    @Query("SELECT COALESCE(MAX(modifiedAt), 0) FROM documents")
    suspend fun getLastUpdatedTimestamp(): Long

    /**
     * Fetches all chunks that have an AI embedding generated.
     */
    @Query("SELECT id, filePath, title, body, fileType, modifiedAt, embedding FROM documents WHERE embedding IS NOT NULL")
    suspend fun getAllVectors(): List<VectorResult>

    @Query("""
        SELECT id, title, substr(body, 1, 250) AS bodySnippet, filePath, fileType, modifiedAt, embedding 
        FROM documents 
        WHERE embedding IS NOT NULL
    """)
    suspend fun getAllEmbeddings(): List<DocumentWithVector>

    // Fetches embeddings only for a specific list of document IDs.
    @Query("SELECT id, embedding, '' as title, '' as bodySnippet, '' as filePath, '' as fileType, 0 as modifiedAt FROM documents WHERE id IN (:docIds) AND embedding IS NOT NULL")
    suspend fun getEmbeddingsForIds(docIds: List<Long>): List<DocumentWithVector>
}

data class DocumentPath(
    val id: Long,
    val filePath: String
)

data class DocumentWithVector(
    val id: Long,
    val title: String,
    val bodySnippet: String, 
    val filePath: String,
    val fileType: String,
    val modifiedAt: Long,
    val embedding: ByteArray
)
