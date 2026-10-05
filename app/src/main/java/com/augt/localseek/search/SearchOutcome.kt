package com.augt.localseek.search

import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.retrieval.FileResult

/**
 * Structured outcome returned by [SearchEngine.search].
 *
 * Encapsulates:
 * - The canonical [RetrievalConfig] executed.
 * - Final aggregated and filtered [FileResult] list.
 * - Granular per-retriever [RetrieverOutcome] mapping.
 * - Stage-specific latencies and peak memory measurements.
 */
data class SearchOutcome(
    val query: String,
    val config: RetrievalConfig,
    val results: List<FileResult>,
    val candidatesByRetriever: Map<RetrieverKind, RetrieverOutcome>,
    val totalLatencyMs: Long,
    val bm25LatencyMs: Long = 0L,
    val denseLatencyMs: Long = 0L,
    val fusionLatencyMs: Long = 0L,
    val rerankLatencyMs: Long = 0L,
    val memoryPeakMb: Float = 0f,
    val indexGeneration: Long = 0L,
    val rerankTimedOut: Boolean = false
)
