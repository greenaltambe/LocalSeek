package com.augt.localseek.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoggedQueryMetric(
    val query: String,
    val bm25LatencyMs: Long,
    val denseLatencyMs: Long,
    val fusionLatencyMs: Long,
    val totalLatencyMs: Long,
    val finalCount: Int,
    val memoryAfterMb: Long,
    val timestamp: Long
)

object PerformanceHistoryStore {
    private val _history = MutableStateFlow<List<LoggedQueryMetric>>(emptyList())
    val history: StateFlow<List<LoggedQueryMetric>> = _history.asStateFlow()

    fun add(metric: LoggedQueryMetric) {
        val updated = (_history.value + metric).takeLast(200)
        _history.value = updated
    }
}

