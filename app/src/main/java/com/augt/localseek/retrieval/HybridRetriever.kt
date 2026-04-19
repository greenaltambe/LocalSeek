package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.VectorUtils
import com.augt.localseek.ml.VectorUtils.toFloatArray
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.Locale



class HybridRetriever(
    private val bm25Retriever: BM25Retriever,
    private val denseRetriever: DenseRetriever,
    context: Context
) {

    private val dao = AppDatabase.getInstance(context).documentDao()

    private val RRF_K = 60
    private val TITLE_BOOST_FACTOR = 1.5f // Title matches are important

    suspend fun search(query: String, topK: Int = 25): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Run Sparse and Dense in parallel
        val sparseResultsDeferred = async { bm25Retriever.search(query, 50) }
        val denseResultsDeferred = async { denseRetriever.search(query, 50) }
        val sparseResults = sparseResultsDeferred.await()
        val denseResults = denseResultsDeferred.await()

        // 2. Combine and calculate RRF scores
        val rrfScores = mutableMapOf<Long, Double>()
        val allDocs = (sparseResults + denseResults).associateBy { it.id }

        sparseResults.forEachIndexed { rank, result ->
            val score = 1.0 / (RRF_K + rank + 1)
            rrfScores[result.id] = rrfScores.getOrDefault(result.id, 0.0) + score
        }
        denseResults.forEachIndexed { rank, result ->
            val score = 1.0 / (RRF_K + rank + 1)
            rrfScores[result.id] = rrfScores.getOrDefault(result.id, 0.0) + score
        }

        // 3. Create initial candidate list, sorted by RRF
        val candidates = rrfScores.entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, score) ->
                allDocs[id]?.copy(score = score.toFloat())
            }

        // 4. Re-rank the top candidates using MMR for diversity
        val diversifiedResults = applyMMR(candidates, topK)

        return@withContext diversifiedResults
    }

    /**
     * Re-ranks a list of candidates to reduce redundancy and improve diversity.
     */
    private suspend fun applyMMR(candidates: List<SearchResult>, limit: Int): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()

        // Fetch embeddings for our top candidates
        val candidateIds = candidates.map { it.id }
        val embeddingsMap = dao.getEmbeddingsForIds(candidateIds)
            .associate { it.id to it.embedding.toFloatArray() }

        val finalRankedList = mutableListOf<SearchResult>()
        val remainingCandidates = candidates.toMutableList()

        // The lambda parameter controls the tradeoff between relevance and diversity.
        // 0.7 means we prefer relevance slightly more.
        val lambda = 0.7f

        // Add the single best result to our list to start
        finalRankedList.add(remainingCandidates.removeAt(0))

        while (finalRankedList.size < limit && remainingCandidates.isNotEmpty()) {
            var bestCandidate: SearchResult? = null
            var bestMmrScore = -Float.MAX_VALUE

            // Find the next best item from the remaining candidates
            for (candidate in remainingCandidates) {
                val relevanceScore = candidate.score // This is the RRF score
                val candidateEmbedding = embeddingsMap[candidate.id] ?: continue

                // Find the maximum similarity between this candidate and the items already in our final list
                var maxSimilarity = 0f
                for (selected in finalRankedList) {
                    val selectedEmbedding = embeddingsMap[selected.id] ?: continue
                    val similarity = VectorUtils.cosineSimilarity(candidateEmbedding, selectedEmbedding)
                    if (similarity > maxSimilarity) {
                        maxSimilarity = similarity
                    }
                }

                // The MMR formula
                val mmrScore = (lambda * relevanceScore) - ((1 - lambda) * maxSimilarity)
                if (mmrScore > bestMmrScore) {
                    bestMmrScore = mmrScore
                    bestCandidate = candidate
                }
            }

            if (bestCandidate != null) {
                finalRankedList.add(bestCandidate)
                remainingCandidates.remove(bestCandidate)
            } else {
                // No more valid candidates to add
                break
            }
        }
        return finalRankedList
    }
}
