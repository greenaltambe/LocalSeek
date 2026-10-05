package com.augt.localseek.data

import androidx.room3.Entity
import androidx.room3.Ignore
import androidx.room3.PrimaryKey

@Entity(tableName = "benchmark_runs")
data class BenchmarkRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runSessionId: String,
    val queryId: String,
    val queryText: String,
    val timestamp: Long,
    val deviceModel: String,
    val androidVersion: String,
    val backend: String,
    val corpusSizeChunks: Int,
    val corpusSizeApps: Int,
    val corpusSizeContacts: Int,
    val latencyBm25Ms: Long,
    val latencyDenseMs: Long,
    val latencyFusionMs: Long,
    val latencyRerankMs: Long?,
    val latencyTotalMs: Long,
    val memoryMbPeak: Float,
    val batteryPctBefore: Int?,
    val batteryPctAfter: Int?,
    val resultIdsJson: String,
    val resultScoresJson: String,
    val resultEntityTypesJson: String,
    val resultTitlesJson: String,
    val resultSnippetsJson: String,
    val configHash: String = "",
    val configJson: String = "{}",
    val indexGeneration: Long = 0L,
    val corpusSizeImages: Int = 0,
    val batteryBand: String = "",
    val thermalStatus: String = ""
) {
    @Ignore
    var repetitionIndex: Int = 0

    @Ignore
    var isValid: Boolean = true

    @Ignore
    /**
     * Flag indicating whether cross-encoder neural reranking exceeded the 500 ms production latency budget.
     * Note: This is an SLA/budget observability flag, NOT a hard timeout or drop flag;
     * runs exceeding 500 ms are retained for full quality evaluation.
     */
    var rerankTimedOut: Boolean = false
}
