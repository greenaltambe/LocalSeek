package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<AppEntity>)

    @Query("SELECT * FROM apps")
    suspend fun getAllApps(): List<AppEntity>

    @Query("SELECT * FROM apps WHERE stableKey = :stableKey LIMIT 1")
    suspend fun getByStableKey(stableKey: String): AppEntity?

    @Query("SELECT stableKey FROM apps")
    suspend fun getAllStableKeys(): List<String>

    @Query("DELETE FROM apps WHERE stableKey IN (:keys)")
    suspend fun deleteByStableKeys(keys: List<String>)

    @Query("DELETE FROM apps")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM apps")
    suspend fun getCount(): Int

    @Query(
        """
        SELECT
            a.id,
            a.packageName,
            a.appName,
            a.textRepresentation,
            a.embedding,
            a.lastIndexedAt,
            a.stableKey,
            bm25(apps_fts) AS score
        FROM apps_fts
        JOIN apps a ON apps_fts.rowid = a.id
        WHERE apps_fts MATCH :query
        ORDER BY score ASC
        LIMIT :limit
        """
    )
    suspend fun searchApps(query: String, limit: Int): List<AppWithScore>
}

data class AppWithScore(
    val id: Long,
    val packageName: String,
    val appName: String,
    val textRepresentation: String = "",
    val embedding: FloatArray? = null,
    val lastIndexedAt: Long = 0L,
    val score: Float,
    val stableKey: String = ""
)
