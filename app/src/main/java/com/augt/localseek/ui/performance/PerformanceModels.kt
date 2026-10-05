package com.augt.localseek.ui.performance

data class PerformanceMetrics(
    val avgLatency: Float = 0f,
    val p95Latency: Float = 0f,
    val totalQueries: Int = 0,
    val memoryUsageMB: Int = 0,
    val latencyBreakdown: LatencyBreakdown = LatencyBreakdown(),
    val qualityMetrics: QualityMetrics = QualityMetrics(),
    val denseIndex: DenseIndexInfo = DenseIndexInfo()
)

data class LatencyBreakdown(
    val queryProcessing: Float = 0f,
    val bm25: Float = 0f,
    val dense: Float = 0f,
    /** Fusion and, when switched on, the cross-encoder rerank (the search log keeps them as one number). */
    val fusion: Float = 0f,
    val total: Float = 1f
)

data class QualityMetrics(
    val avgResultsPerQuery: Int = 0,
    val highRecallPercentage: Int = 0,
    val emptyResultRate: Int = 0
)

/** The dense index the last search used (see AutoVectorIndex): its kind and the number of chunk vectors it saw. */
data class DenseIndexInfo(
    val description: String = "No dense search yet",
    val vectorCount: Int = 0
)

data class SearchQueryMetric(
    val query: String,
    val totalLatency: Float,
    val resultCount: Int,
    val timestamp: Long
)

