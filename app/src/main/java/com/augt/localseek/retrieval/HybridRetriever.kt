package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HybridRetriever(
    private val bm25Retriever: BM25Retriever,
    private val denseRetriever: DenseRetriever
) {

    suspend fun search(query: String): List<HybridResult> {
        if (query.isBlank()) return emptyList()

        // 1. Run both retrievers in parallel for maximum speed.
        val (sparseResults, denseResults) = coroutineScope {
            val sparseJob = async { bm25Retriever.search(query, 50) }
            val denseJob = async { denseRetriever.search(query, 50) }
            Pair(sparseJob.await(), denseJob.await())
        }

        // 2. Create maps for quick rank lookups (optional if not used in this specific version)
        val sparseRankings = sparseResults.withIndex().associate { (i, r) -> r.id to (i + 1) }
        val denseRankings = denseResults.withIndex().associate { (i, r) -> r.id to (i + 1) }

        // 3. Combine all unique documents from both lists.
        val allDocs = (sparseResults + denseResults).associateBy { it.id }.values

        return allDocs.map { doc ->
            HybridResult(
                result = doc,
                bm25Score = sparseResults.find { it.id == doc.id }?.score ?: 0f,
                denseScore = denseResults.find { it.id == doc.id }?.score ?: 0f
            )
        }
    }
}
