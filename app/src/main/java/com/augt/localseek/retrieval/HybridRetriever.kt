package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class HybridRetriever(
    private val bm25Retriever: BM25Retriever,
    private val denseRetriever: DenseRetriever
) {

    private val k = 60 // RRF constant
    private val TITLE_BOOST_FACTOR = 2.0f // Multiplier for title matches

    suspend fun search(query: String, topK: Int = 25): List<HybridResult> {
        if (query.isBlank()) return emptyList()

        // 1. Run Sparse and Dense in parallel
        val (sparseResults, denseResults) = coroutineScope {
            val sparseJob = async { bm25Retriever.search(query, 50) }
            val denseJob = async { denseRetriever.search(query, 50) }
            Pair(sparseJob.await(), denseJob.await())
        }

        // 2. Maps for rank lookups
        val sparseRankings = sparseResults.withIndex().associate { (i, r) -> r.id to i + 1 }
        val denseRankings = denseResults.withIndex().associate { (i, r) -> r.id to i + 1 }

        // 3. Combine unique docs
        val allDocs = (sparseResults + denseResults).associateBy { it.id }.values

        // 4. Calculate RRF Score + Apply Title Boosting
        val candidates = allDocs.map { doc ->
            val sparseRank = sparseRankings[doc.id] ?: Int.MAX_VALUE
            val denseRank = denseRankings[doc.id] ?: Int.MAX_VALUE

            // Base RRF Score
            var finalRrfScore = (1.0f / (k + sparseRank)) + (1.0f / (k + denseRank))

            // --- HEURISTIC: TITLE BOOSTING ---
            if (containsMatchInTitle(doc.title, query)) {
                finalRrfScore *= TITLE_BOOST_FACTOR
            }

            // Use the best available metadata (usually from Sparse)
            val baseResult = sparseResults.find { it.id == doc.id } ?: doc
            
            HybridResult(
                doc = baseResult.copy(score = finalRrfScore),
                bm25Score = sparseResults.find { it.id == doc.id }?.score ?: 0f,
                denseScore = denseResults.find { it.id == doc.id }?.score ?: 0f
            )
        }

        // Sort by the boosted score
        return candidates.sortedByDescending { it.doc.score }.take(topK)
    }

    /**
     * Checks if any word in the query exists in the title.
     * Simple but extremely effective for user satisfaction.
     */
    private fun containsMatchInTitle(title: String, query: String): Boolean {
        val cleanTitle = title.lowercase(Locale.ROOT)
        val queryWords = query.lowercase(Locale.ROOT)
            .split("\\s+".toRegex())
            .filter { it.length > 2 } // Ignore tiny words like "is", "of"

        for (word in queryWords) {
            if (cleanTitle.contains(word)) return true
        }
        return false
    }
}
