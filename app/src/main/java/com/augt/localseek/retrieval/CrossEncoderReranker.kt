package com.augt.localseek.retrieval

import android.content.Context
import android.util.LruCache
import android.util.Log
import com.augt.localseek.ml.CrossEncoder
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CrossEncoderReranker(context: Context) {

    companion object {
        private const val TAG = "CrossEncoderReranker"
        private const val ENABLE_RERANK = true
        private const val RERANK_TOP_K = 100
        private const val RETURN_TOP_K = 20
        private const val MAX_RERANK_TIME_MS = 500L
        private const val CROSS_WEIGHT = 0.7f
        private const val INITIAL_WEIGHT = 0.3f
    }

    private val crossEncoder = CrossEncoder(context)
    private val scoreCache = LruCache<String, Float>(500)

    suspend fun rerank(query: String, candidates: List<SearchResult>): List<SearchResult> = withContext(Dispatchers.Default) {
        if (!ENABLE_RERANK || !crossEncoder.isAvailable || candidates.isEmpty()) {
            return@withContext candidates.take(RETURN_TOP_K)
        }

        val topCandidates = candidates.take(RERANK_TOP_K)
        val reranked = withTimeoutOrNull(MAX_RERANK_TIME_MS) {
            topCandidates.map { candidate ->
                val cacheKey = "${query.lowercase()}::${candidate.id}"
                val crossScore = scoreCache.get(cacheKey) ?: crossEncoder.score(query, candidate.snippet).also {
                    scoreCache.put(cacheKey, it)
                }

                val hybridScore = (CROSS_WEIGHT * crossScore) + (INITIAL_WEIGHT * candidate.score)
                Log.v(TAG, "rerank id=${candidate.id} initial=${candidate.score} cross=$crossScore final=$hybridScore")
                candidate.copy(score = hybridScore)
            }
        }

        if (reranked == null) {
            Log.w(TAG, "Reranking timed out after ${MAX_RERANK_TIME_MS}ms; using fused ranking")
            return@withContext topCandidates.take(RETURN_TOP_K)
        }

        reranked
            .sortedByDescending { it.score }
            .take(RETURN_TOP_K)
    }

    fun close() {
        crossEncoder.close()
    }
}
