package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ImageEntity>)

    @Query("SELECT * FROM images")
    suspend fun getAllImages(): List<ImageEntity>

    @Query("SELECT * FROM images WHERE stableKey = :stableKey LIMIT 1")
    suspend fun getByStableKey(stableKey: String): ImageEntity?

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getImagesByIds(ids: List<Long>): List<ImageEntity>

    @Query("SELECT * FROM images WHERE stableKey IN (:stableKeys)")
    suspend fun getImagesByStableKeys(stableKeys: List<String>): List<ImageEntity>

    @Query("SELECT stableKey FROM images")
    suspend fun getAllStableKeys(): List<String>

    @Query("DELETE FROM images WHERE stableKey IN (:keys)")
    suspend fun deleteByStableKeys(keys: List<String>)

    @Query("SELECT mediaStoreId FROM images")
    suspend fun getAllMediaStoreIds(): List<Long>

    @Query("DELETE FROM images WHERE mediaStoreId = :mediaStoreId")
    suspend fun deleteByMediaStoreId(mediaStoreId: Long)

    @Query("DELETE FROM images WHERE mediaStoreId IN (:mediaStoreIds)")
    suspend fun deleteByMediaStoreIds(mediaStoreIds: List<Long>)

    @Query("DELETE FROM images")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM images")
    suspend fun getCount(): Int

    // Keyset pagination matching ChunkDao pattern for memory-conscious streaming
    @Query(
        """
        SELECT id, mediaStoreId, uri, displayName, embedding
        FROM images
        WHERE id > :lastId AND embedding IS NOT NULL
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun getEmbeddingsPage(limit: Int, lastId: Long): List<ImageEmbeddingPage>
}

data class ImageEmbeddingPage(
    val id: Long,
    val mediaStoreId: Long,
    val uri: String,
    val displayName: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageEmbeddingPage

        if (id != other.id) return false
        if (mediaStoreId != other.mediaStoreId) return false
        if (uri != other.uri) return false
        if (displayName != other.displayName) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mediaStoreId.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
