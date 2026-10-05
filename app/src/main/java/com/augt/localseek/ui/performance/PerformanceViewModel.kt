package com.augt.localseek.ui.performance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.logging.PerformanceHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PerformanceViewModel(application: Application) : AndroidViewModel(application) {

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<SearchQueryMetric>>(emptyList())
    val searchHistory: StateFlow<List<SearchQueryMetric>> = _searchHistory.asStateFlow()

    init {
        viewModelScope.launch {
            PerformanceHistoryStore.history.collect { history ->
                val mapped = history.map {
                    SearchQueryMetric(
                        query = it.query,
                        totalLatency = it.totalLatencyMs.toFloat(),
                        resultCount = it.finalCount,
                        timestamp = it.timestamp
                    )
                }
                _searchHistory.value = mapped
                _metrics.value = computeMetrics(history).copy(denseIndex = denseIndexInfo())
            }
        }
    }

    private fun denseIndexInfo(): DenseIndexInfo {
        val status = (getApplication<Application>() as? com.augt.localseek.LocalSeekApplication)?.appContainer?.autoVectorIndex?.status()
            ?: return DenseIndexInfo()
        val description = when (status.path) {
            com.augt.localseek.search.vector.AutoVectorIndex.Path.NONE -> "No dense search yet"
            com.augt.localseek.search.vector.AutoVectorIndex.Path.EXACT_MEMORY -> "Exact search in memory"
            com.augt.localseek.search.vector.AutoVectorIndex.Path.BINARY_RESCORE -> "Binary shortlist + float rescoring (k' = 200)"
            com.augt.localseek.search.vector.AutoVectorIndex.Path.DATABASE_SCAN -> "Exact scan of the database (too large for memory)"
        }
        return DenseIndexInfo(description, status.vectorCount)
    }

    fun exportMetrics() {
        // Placeholder: can export JSON/CSV in follow-up phase.
    }

    private fun computeMetrics(history: List<com.augt.localseek.logging.LoggedQueryMetric>): PerformanceMetrics {
        if (history.isEmpty()) return PerformanceMetrics()

        val totalQueries = history.size
        val totalLatencies = history.map { it.totalLatencyMs.toFloat() }
        val avgLatency = totalLatencies.average().toFloat()
        val sorted = totalLatencies.sorted()
        val p95Index = ((sorted.size - 1) * 0.95f).toInt().coerceIn(0, sorted.lastIndex)
        val p95 = sorted[p95Index]

        val avgBm25 = history.map { it.bm25LatencyMs.toFloat() }.average().toFloat()
        val avgDense = history.map { it.denseLatencyMs.toFloat() }.average().toFloat()
        val avgFusion = history.map { it.fusionLatencyMs.toFloat() }.average().toFloat()
        val avgQueryProcessing = (avgLatency - avgBm25 - avgDense - avgFusion).coerceAtLeast(0f)

        val avgResultCount = history.map { it.finalCount }.average().toInt()
        val highRecall = (history.count { it.finalCount > 10 } * 100f / totalQueries).toInt()
        val emptyRate = (history.count { it.finalCount == 0 } * 100f / totalQueries).toInt()
        val avgMemory = history.map { it.memoryAfterMb }.average().toInt()

        return PerformanceMetrics(
            avgLatency = avgLatency,
            p95Latency = p95,
            totalQueries = totalQueries,
            memoryUsageMB = avgMemory,
            latencyBreakdown = LatencyBreakdown(
                queryProcessing = avgQueryProcessing,
                bm25 = avgBm25,
                dense = avgDense,
                fusion = avgFusion,
                total = avgLatency.coerceAtLeast(1f)
            ),
            qualityMetrics = QualityMetrics(
                avgResultsPerQuery = avgResultCount,
                highRecallPercentage = highRecall,
                emptyResultRate = emptyRate
            )
        )
    }
}

