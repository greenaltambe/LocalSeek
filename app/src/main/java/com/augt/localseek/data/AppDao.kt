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

    @Query("DELETE FROM apps")
    suspend fun clearAll()

    @Query(
        """
        SELECT
            a.id,
            a.packageName,
            a.appName,
            a.textRepresentation,
            a.embedding,
            a.lastIndexedAt,
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
    val textRepresentation: String,
    val embedding: FloatArray?,
    val lastIndexedAt: Long,
    val score: Float
)
