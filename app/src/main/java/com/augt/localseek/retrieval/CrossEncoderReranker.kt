package com.augt.localseek.retrieval

import android.content.Context
import android.util.LruCache
import android.util.Log
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.ml.CrossEncoder
import com.augt.localseek.ml.TokenizerMode
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CrossEncoderReranker(
    context: Context,
    val crossEncoder: CrossEncoder,
    private val ownsResources: Boolean = false
) {

    constructor(context: Context) : this(
        context = context,
        crossEncoder = (context.applicationContext as? LocalSeekApplication)?.appContainer?.modelRegistry?.crossEncoder
            ?: CrossEncoder(context),
        ownsResources = (context.applicationContext as? LocalSeekApplication)?.appContainer == null
    )

    data class RerankOutcome(
        val results: List<SearchResult>,
        val timedOut: Boolean = false
    )

    companion object {
        private const val TAG = "CrossEncoderReranker"
        private const val ENABLE_RERANK = true
        private const val RERANK_TOP_K = 100
        private const val RETURN_TOP_K = 20
        const val DEFAULT_MAX_RERANK_TIME_MS = 500L
        private const val CROSS_WEIGHT = 0.7f
        private const val INITIAL_WEIGHT = 0.3f

        /**
         * Calibrates unbounded cross-encoder logits into the bounded [0.0, 1.0] interval.
         * Prevents unbounded logit magnitude from dwarfing bounded initial fused scores (especially under RRF).
         */
        fun sigmoid(logit: Float): Float {
            return (1.0 / (1.0 + kotlin.math.exp(-logit.toDouble()))).toFloat()
        }
    }

    private val scoreCache = LruCache<String, Float>(500)

    fun clearCache() {
        scoreCache.evictAll()
    }

    suspend fun rerankDetailed(
        query: String,
        candidates: List<SearchResult>,
        topK: Int = RERANK_TOP_K,
        returnTopK: Int = RETURN_TOP_K,
        crossWeight: Float = CROSS_WEIGHT,
        initialWeight: Float = INITIAL_WEIGHT,
        tokenizerMode: TokenizerMode = TokenizerMode.FIXED,
        maxRerankTimeMs: Long = DEFAULT_MAX_RERANK_TIME_MS
    ): RerankOutcome = withContext(Dispatchers.Default) {
        if (!ENABLE_RERANK || !crossEncoder.isAvailable || candidates.isEmpty()) {
            return@withContext RerankOutcome(candidates.take(returnTopK), timedOut = false)
        }

        val topCandidates = candidates.take(topK)
        val fusedScores = topCandidates.map { it.score.toDouble() }
        val normFusedScores = ScoreNormalizer.minMaxNorm(fusedScores)

        val reranked = withTimeoutOrNull(maxRerankTimeMs) {
            val out = mutableListOf<SearchResult>()
            for ((index, candidate) in topCandidates.withIndex()) {
                ensureActive()
                val idPart = if (candidate.stableKey.isNotBlank()) candidate.stableKey else candidate.id.toString()
                val cacheKey = "${query.lowercase()}::${candidate.entityType}::$idPart"
                val cached = scoreCache.get(cacheKey)
                val crossLogit = if (cached != null) {
                    cached
                } else {
                    val s = crossEncoder.score(query, candidate.snippet, tokenizerMode)
                    scoreCache.put(cacheKey, s)
                    s
                }

                // Calibrate raw cross-encoder logit to [0, 1] using sigmoid before linear interpolation
                val calibratedCrossScore = sigmoid(crossLogit)
                val normFused = normFusedScores[index].toFloat()
                val hybridScore = (crossWeight * calibratedCrossScore) + (initialWeight * normFused)
                Log.v(TAG, "rerank id=${candidate.id} stableKey=${candidate.stableKey} type=${candidate.entityType} initial=${candidate.score} normFused=$normFused crossLogit=$crossLogit calibrated=$calibratedCrossScore final=$hybridScore")
                out.add(candidate.copy(score = hybridScore))
            }
            out
        }

        if (reranked == null) {
            Log.w(TAG, "Reranking timed out after ${maxRerankTimeMs}ms; using fused ranking")
            return@withContext RerankOutcome(topCandidates.take(returnTopK), timedOut = true)
        }

        val sorted = reranked
            .sortedWith(
                compareByDescending<SearchResult> { it.score }
                    .thenBy { it.stableKey }
                    .thenBy { it.id }
            )
            .take(returnTopK)

        RerankOutcome(sorted, timedOut = false)
    }

    suspend fun rerank(
        query: String,
        candidates: List<SearchResult>,
        topK: Int = RERANK_TOP_K,
        returnTopK: Int = RETURN_TOP_K,
        crossWeight: Float = CROSS_WEIGHT,
        initialWeight: Float = INITIAL_WEIGHT,
        tokenizerMode: TokenizerMode = TokenizerMode.FIXED,
        maxRerankTimeMs: Long = DEFAULT_MAX_RERANK_TIME_MS
    ): List<SearchResult> = rerankDetailed(
        query = query,
        candidates = candidates,
        topK = topK,
        returnTopK = returnTopK,
        crossWeight = crossWeight,
        initialWeight = initialWeight,
        tokenizerMode = tokenizerMode,
        maxRerankTimeMs = maxRerankTimeMs
    ).results

    fun close() {
        if (ownsResources) {
            crossEncoder.close()
        }
    }
}
