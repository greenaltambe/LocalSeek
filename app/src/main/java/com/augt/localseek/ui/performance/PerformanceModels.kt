package com.augt.localseek.ui.performance

data class PerformanceMetrics(
    val avgLatency: Float = 0f,
    val p95Latency: Float = 0f,
    val totalQueries: Int = 0,
    val memoryUsageMB: Int = 0,
    val latencyBreakdown: LatencyBreakdown = LatencyBreakdown(),
    val qualityMetrics: QualityMetrics = QualityMetrics(),
    val lshConfig: LshConfiguration = LshConfiguration()
)

data class LatencyBreakdown(
    val queryProcessing: Float = 0f,
    val bm25: Float = 0f,
    val dense: Float = 0f,
    val fusion: Float = 0f,
    val reranking: Float = 0f,
    val total: Float = 1f
)

data class QualityMetrics(
    val avgResultsPerQuery: Int = 0,
    val avgTopScore: Float = 0f,
    val highRecallPercentage: Int = 0,
    val emptyResultRate: Int = 0
)

data class LshConfiguration(
    val numTables: Int = 10,
    val hashBits: Int = 12,
    val memoryMode: String = "AUTO",
    val datasetSize: Int = 0,
    val avgBucketSize: Int = 0
)

data class SearchQueryMetric(
    val query: String,
    val totalLatency: Float,
    val resultCount: Int,
    val timestamp: Long
)

