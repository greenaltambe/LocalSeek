package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class HybridRetriever(
    private val bm25Retriever: BM25Retriever,
    private val denseRetriever: DenseRetriever
) {

    private val k = 60 // Standard RRF constant

    suspend fun search(query: String, topK: Int = 25): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. Run both retrievers in parallel for maximum speed.
        val (sparseResults, denseResults) = coroutineScope {
            val sparseJob = async { bm25Retriever.search(query, 50) }
            val denseJob = async { denseRetriever.search(query, 50) }
            Pair(sparseJob.await(), denseJob.await())
        }

        // 2. Create maps for quick rank lookups.
        val sparseRankings = sparseResults.withIndex().associate { (i, r) -> r.id to i + 1 }
        val denseRankings = denseResults.withIndex().associate { (i, r) -> r.id to i + 1 }

        // 3. Combine all unique documents from both lists.
        val allDocs = (sparseResults + denseResults).associateBy { it.id }.values

        // 4. Calculate the RRF score for each document.
        val fusedResults = allDocs.map { doc ->
            val sparseRank = sparseRankings[doc.id] ?: Int.MAX_VALUE
            val denseRank = denseRankings[doc.id] ?: Int.MAX_VALUE

            val rrfScore = (1.0f / (k + sparseRank)) + (1.0f / (k + denseRank))

            // We'll use the title/snippet from the BM25 result if available,
            // as it often has better keyword highlighting.
            val finalDoc = sparseResults.find { it.id == doc.id } ?: doc

            finalDoc.copy(score = rrfScore.toFloat())
        }

        // 5. Sort by the final RRF score and return the best results.
        return fusedResults.sortedByDescending { it.score }.take(topK)
    }
}