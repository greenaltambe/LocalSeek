package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DocumentChunk>)

    @Query("DELETE FROM document_chunks WHERE parentFileId = :parentFileId")
    suspend fun deleteByParentFileId(parentFileId: Long)

    @Query("SELECT COUNT(*) FROM document_chunks WHERE parentFileId = :parentFileId")
    suspend fun countChunksForFile(parentFileId: Long): Int

    @Query(
        """
        SELECT id, embedding
        FROM document_chunks
        WHERE embedding IS NOT NULL
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getEmbeddingsPage(limit: Int, offset: Int): List<ChunkEmbedding>

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.parentFileId,
            c.text,
            c.embedding,
            d.filePath,
            d.title,
            d.fileType,
            d.modifiedAt
        FROM document_chunks c
        JOIN documents d ON c.parentFileId = d.id
        WHERE c.id IN (:chunkIds)
        """
    )
    suspend fun getChunkMetadataByIds(chunkIds: List<Long>): List<ChunkMetadata>

    @Query(
        """
        SELECT
            c.id AS chunkId,
            c.parentFileId,
            c.chunkIndex,
            c.text,
            c.startOffset,
            c.endOffset,
            d.filePath,
            d.title,
            d.fileType,
            d.modifiedAt,
            bm25(chunks_fts) AS score
        FROM chunks_fts
        JOIN document_chunks c ON chunks_fts.rowid = c.id
        JOIN documents d ON c.parentFileId = d.id
        WHERE chunks_fts MATCH :query
        ORDER BY score ASC
        LIMIT :limit
        """
    )
    suspend fun searchChunks(query: String, limit: Int): List<ChunkWithMetadata>
}

data class ChunkEmbedding(
    val id: Long,
    val embedding: FloatArray
)

data class ChunkMetadata(
    val chunkId: Long,
    val parentFileId: Long,
    val text: String,
    val embedding: FloatArray?,
    val filePath: String,
    val title: String,
    val fileType: String,
    val modifiedAt: Long
)

data class ChunkWithMetadata(
    val chunkId: Long,
    val parentFileId: Long,
    val chunkIndex: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val filePath: String,
    val title: String,
    val fileType: String,
    val modifiedAt: Long,
    val score: Float
)

