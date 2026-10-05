package com.augt.localseek.search

import com.augt.localseek.model.SearchResult

/**
 * Identifies the backend retriever component.
 */
enum class RetrieverKind {
    BM25,
    DENSE,
    IMAGE
}

/**
 * Structured outcome of an individual retriever execution.
 *
 * Distinctly separates:
 * - Ran: executed successfully, producing a list of candidates (can be empty) and elapsed latency.
 * - Skipped: was intentionally bypassed with an explicit reason (e.g. disabled by config, or BM25 skip threshold reached).
 * - Failed: encountered an unhandled exception or critical error during retrieval.
 */
sealed class RetrieverOutcome {
    data class Ran(
        val candidates: List<SearchResult>,
        val latencyMs: Long
    ) : RetrieverOutcome()

    data class Skipped(
        val reason: String
    ) : RetrieverOutcome()

    data class Failed(
        val cause: String
    ) : RetrieverOutcome()
}
