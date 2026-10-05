package com.augt.localseek.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface QrelsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(judgment: QrelsJudgment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(judgments: List<QrelsJudgment>)

    @Update
    suspend fun update(judgment: QrelsJudgment)

    @Query("SELECT * FROM qrels_judgments WHERE queryId = :queryId")
    suspend fun getForQuery(queryId: String): List<QrelsJudgment>

    @Query("SELECT * FROM qrels_judgments WHERE queryId = :queryId AND resultId = :resultId LIMIT 1")
    suspend fun getJudgment(queryId: String, resultId: String): QrelsJudgment?

    @Query("SELECT * FROM qrels_judgments ORDER BY timestamp DESC")
    suspend fun getAll(): List<QrelsJudgment>

    @Query("DELETE FROM qrels_judgments WHERE queryId = :queryId")
    suspend fun deleteForQuery(queryId: String)
}
