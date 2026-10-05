package com.augt.localseek.retrieval

import com.augt.localseek.data.BenchmarkRunEntity
import org.json.JSONArray
import kotlin.random.Random

/**
 * Data class for a candidate in the pooled results for relevance labeling.
 */
data class PooledCandidate(
    val resultId: String,
    val entityType: String,
    val title: String,
    val snippet: String
)

/**
 * Builds a pooled set of results for a query across all benchmarked backends
 * for human relevance labeling (TREC-style pooling).
 */
object QrelsPoolBuilder {

    /**
     * Number of top results to take from each backend for the pool.
     */
    private const val POOL_SIZE_PER_BACKEND = 15

    /**
     * Builds a deduplicated, shuffled pool of results from multiple benchmark runs of the same query.
     *
     * @param runs A list of BenchmarkRunEntity objects, typically one per backend for a single query.
     * @param seed Optional seed for shuffling to ensure reproducibility in tests if needed.
     * @return A shuffled list of unique PooledCandidate objects.
     */
    fun buildPool(runs: List<BenchmarkRunEntity>, seed: Long? = null): List<PooledCandidate> {
        val poolMap = mutableMapOf<String, PooledCandidate>()

        runs.forEach { run ->
            val ids = parseJsonArray(run.resultIdsJson)
            val types = parseJsonArray(run.resultEntityTypesJson)
            val titles = parseJsonArray(run.resultTitlesJson)
            val snippets = parseJsonArray(run.resultSnippetsJson)

            // Take top-N from this backend
            val limit = minOf(ids.size, POOL_SIZE_PER_BACKEND)
            for (i in 0 until limit) {
                val resultId = ids[i]
                if (!poolMap.containsKey(resultId)) {
                    poolMap[resultId] = PooledCandidate(
                        resultId = resultId,
                        entityType = types.getOrNull(i) ?: "UNKNOWN",
                        title = titles.getOrNull(i) ?: "",
                        snippet = snippets.getOrNull(i) ?: ""
                    )
                }
            }
        }

        val finalPool = poolMap.values.toList()
        
        // Shuffle the final list to avoid position-bias during human labeling.
        // We use a random seed (based on system time by default) so the labeler 
        // doesn't see results in the order they were ranked by any particular backend.
        // Anchoring bias occurs when a labeler is influenced by the initial rank 
        // or the order of presentation, assuming earlier items are more likely to be relevant.
        val random = if (seed != null) Random(seed) else Random
        return finalPool.shuffled(random)
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
