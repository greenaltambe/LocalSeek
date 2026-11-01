package com.augt.localseek.data.study

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Tables of the multi-user study (task AS). They exist in every build (empty outside the study build) and are created by a
 * migration that only CREATES them. Query text lives only here, on the phone, until "Delete study data"; the export never
 * contains it.
 */

/** One row (id = 1): the participant's session. */
@Entity(tableName = "study_session")
data class StudySessionEntity(
    @PrimaryKey val id: Int = 1,
    val participantCode: String,
    val consentAt: Long,
    /** StudyStage name. */
    val stage: String,
    val indexStartMs: Long = 0,
    val indexEndMs: Long = 0,
    val files: Int = 0,
    val chunks: Int = 0,
    val apps: Int = 0,
    val contacts: Int = 0,
    val batteryStart: Int = -1,
    val chargingStart: Boolean = false,
    val batteryEnd: Int = -1,
    val chargingEnd: Boolean = false,
    val freeUseStartMs: Long = 0,
    /** Ten answers 1..5 separated by commas; empty until answered. */
    val susAnswers: String = ""
)

@Entity(tableName = "study_query", indices = [Index(value = ["idx"], unique = true)])
data class StudyQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 1..n, in the order typed. */
    val idx: Int,
    val queryText: String,
    /** StudyKind name. */
    val kind: String,
    /** StudyStyle name. */
    val style: String,
    val wordCount: Int,
    /** Seed of the shuffle that orders the pooled list (reproducible on resume). */
    val seed: Long,
    val createdAtMs: Long,
    val pooledCount: Int = 0,
    val latencyE1Ms: Long = -1,
    val latencyE2Ms: Long = -1,
    val latencyE9Ms: Long = -1,
    /** -1 = not asked yet, 0 = no, 1 = yes. */
    val found: Int = -1,
    val completed: Boolean = false
)

/** Judgement of one pooled item. [itemKey] is "ENTITY:stableKey-or-path"; ranks are 1..10 within each arm, 0 = not in its top 10. */
@Entity(tableName = "study_judgment", indices = [Index(value = ["queryId", "itemKey"], unique = true)])
data class StudyJudgmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryId: Long,
    val itemKey: String,
    val entityType: String,
    val rankE1: Int,
    val rankE2: Int,
    val rankE9: Int,
    /** null = not judged yet. */
    val relevant: Int? = null
)
