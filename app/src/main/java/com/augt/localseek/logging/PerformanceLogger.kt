package com.augt.localseek.logging

import android.util.Log
import androidx.annotation.VisibleForTesting

class PerformanceLogger(
    private val tag: String = "SearchPerf"
) {

    fun memoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedBytes / (1024L * 1024L)
    }

    fun logQuery(
        query: String,
        bm25LatencyMs: Long,
        denseLatencyMs: Long,
        fusionLatencyMs: Long,
        totalLatencyMs: Long,
        bm25Count: Int,
        denseCount: Int,
        finalCount: Int,
        memoryBeforeMb: Long,
        memoryAfterMb: Long
    ) {
        Log.i(
            tag,
            formatLine(
                query = query,
                bm25LatencyMs = bm25LatencyMs,
                denseLatencyMs = denseLatencyMs,
                fusionLatencyMs = fusionLatencyMs,
                totalLatencyMs = totalLatencyMs,
                bm25Count = bm25Count,
                denseCount = denseCount,
                finalCount = finalCount,
                memoryBeforeMb = memoryBeforeMb,
                memoryAfterMb = memoryAfterMb
            )
        )

        PerformanceHistoryStore.add(
            LoggedQueryMetric(
                query = query,
                bm25LatencyMs = bm25LatencyMs,
                denseLatencyMs = denseLatencyMs,
                fusionLatencyMs = fusionLatencyMs,
                totalLatencyMs = totalLatencyMs,
                finalCount = finalCount,
                memoryAfterMb = memoryAfterMb,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    @VisibleForTesting
    internal fun formatLine(
        query: String,
        bm25LatencyMs: Long,
        denseLatencyMs: Long,
        fusionLatencyMs: Long,
        totalLatencyMs: Long,
        bm25Count: Int,
        denseCount: Int,
        finalCount: Int,
        memoryBeforeMb: Long,
        memoryAfterMb: Long
    ): String {
        return "[PERF] Query: \"$query\" | BM25: ${bm25LatencyMs}ms ($bm25Count results) | " +
            "Dense: ${denseLatencyMs}ms ($denseCount results) | Fusion: ${fusionLatencyMs}ms | " +
            "Total: ${totalLatencyMs}ms ($finalCount results) | Mem: ${memoryBeforeMb}MB -> ${memoryAfterMb}MB"
    }
}

