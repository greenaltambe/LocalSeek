package com.augt.localseek.data.study

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface StudyDao {
    @Query("SELECT * FROM study_session WHERE id = 1")
    suspend fun session(): StudySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: StudySessionEntity)

    @Query("SELECT * FROM study_query ORDER BY idx")
    suspend fun queries(): List<StudyQueryEntity>

    @Query("SELECT * FROM study_query WHERE id = :id")
    suspend fun query(id: Long): StudyQueryEntity?

    @Insert
    suspend fun insertQuery(query: StudyQueryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveQuery(query: StudyQueryEntity)

    @Query("SELECT * FROM study_judgment WHERE queryId = :queryId")
    suspend fun judgments(queryId: Long): List<StudyJudgmentEntity>

    @Query("SELECT * FROM study_judgment ORDER BY queryId, id")
    suspend fun allJudgments(): List<StudyJudgmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJudgments(rows: List<StudyJudgmentEntity>)

    @Query("UPDATE study_judgment SET relevant = :relevant WHERE queryId = :queryId AND itemKey = :itemKey")
    suspend fun setRelevant(queryId: Long, itemKey: String, relevant: Int)

    @Query("DELETE FROM study_judgment")
    suspend fun deleteJudgments()

    @Query("DELETE FROM study_query")
    suspend fun deleteQueries()

    @Query("DELETE FROM study_session")
    suspend fun deleteSession()
}
