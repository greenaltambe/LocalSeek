package com.augt.localseek.data

import androidx.room3.Entity
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
    val resultEntityTypesJson: String
)
