package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

@Dao
interface BenchmarkRunDao {
    @Insert
    suspend fun insert(record: BenchmarkRunEntity)

    @Query("SELECT * FROM benchmark_runs ORDER BY timestamp DESC")
    suspend fun getAll(): List<BenchmarkRunEntity>

    @Query("SELECT * FROM benchmark_runs WHERE runSessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<BenchmarkRunEntity>

    @Query("DELETE FROM benchmark_runs")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM benchmark_runs")
    suspend fun getCount(): Int
}
