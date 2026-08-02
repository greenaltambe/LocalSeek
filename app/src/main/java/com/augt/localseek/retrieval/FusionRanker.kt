package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import kotlin.math.exp
import kotlin.math.sqrt

enum class FusionMode {
    GLOBAL_NORMALIZATION,
    PER_TYPE_NORMALIZATION,
    PER_TYPE_WITH_THRESHOLD
}

data class FusionCandidate(
    val id: Long,
    val title: String,
    val snippet: String,
    val filePath: String,
    val fileType: String,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val bm25Score: Double? = null,
    val denseScore: Double? = null,
    val embedding: FloatArray? = null,
    val finalScore: Double = 0.0,
    val entityType: EntityType = EntityType.FILE
)

class FusionRanker {
    private val wBm25 = 0.45
    private val wDense = 0.35
    private val wRecency = 0.10
    private val wTitle = 0.10

    companion object {
        const val DENSE_THRESHOLD_FLOOR = 0.35
        const val BM25_THRESHOLD_FRACTION = 0.5
    }

    fun rank(
        query: String,
        results: List<FusionCandidate>,
        mode: FusionMode = FusionMode.GLOBAL_NORMALIZATION
    ): List<FusionCandidate> {
        if (results.isEmpty()) return emptyList()

        return when (mode) {
            FusionMode.GLOBAL_NORMALIZATION -> rankGlobal(query, results)
            FusionMode.PER_TYPE_NORMALIZATION -> rankPerType(query, results)
            FusionMode.PER_TYPE_WITH_THRESHOLD -> rankPerTypeWithThreshold(query, results)
        }
    }

    private fun rankGlobal(query: String, results: List<FusionCandidate>): List<FusionCandidate> {
        val bm25Scores = results.map { it.bm25Score ?: 0.0 }
        val denseScores = results.map { it.denseScore ?: 0.0 }
        val recencyScores = results.map { calculateRecency(it.modifiedAt) }

        val bm25Norm = ScoreNormalizer.minMaxNorm(bm25Scores)
        val denseNorm = ScoreNormalizer.minMaxNorm(denseScores)
        val recencyNorm = ScoreNormalizer.minMaxNorm(recencyScores)

        return results.mapIndexed { i, result ->
            val score = combineScores(query, result, bm25Norm[i], denseNorm[i], recencyNorm[i])
            result.copy(finalScore = score)
        }.sortedByDescending { it.finalScore }
    }

    private fun rankPerType(query: String, results: List<FusionCandidate>): List<FusionCandidate> {
        val bm25NormMap = ScoreNormalizer.minMaxNormPerGroup(results, { it.bm25Score ?: 0.0 }, { it.entityType })
        val denseNormMap = ScoreNormalizer.minMaxNormPerGroup(results, { it.denseScore ?: 0.0 }, { it.entityType })
        val recencyNormMap = ScoreNormalizer.minMaxNormPerGroup(results, { calculateRecency(it.modifiedAt) }, { it.entityType })

        return results.map { result ->
            val key = Pair(result.entityType, result.id)
            val bm25Norm = bm25NormMap[key] ?: 0.5
            val denseNorm = denseNormMap[key] ?: 0.5
            val recencyNorm = recencyNormMap[key] ?: 0.5

            val score = combineScores(query, result, bm25Norm, denseNorm, recencyNorm)
            result.copy(finalScore = score)
        }.sortedByDescending { it.finalScore }
    }

    private fun rankPerTypeWithThreshold(query: String, results: List<FusionCandidate>): List<FusionCandidate> {
        val topBm25 = results.maxOfOrNull { it.bm25Score ?: 0.0 } ?: 0.0
        val bm25Floor = topBm25 * BM25_THRESHOLD_FRACTION

        val validGroups = results.groupBy { it.entityType }.filter { (_, candidates) ->
            val bestBm25 = candidates.maxOfOrNull { it.bm25Score ?: 0.0 } ?: 0.0
            val bestDense = candidates.maxOfOrNull { it.denseScore ?: 0.0 } ?: 0.0
            
            bestBm25 >= bm25Floor || bestDense >= DENSE_THRESHOLD_FLOOR
        }.keys

        val filteredResults = results.filter { it.entityType in validGroups }
        if (filteredResults.isEmpty()) return emptyList()

        return rankPerType(query, filteredResults)
    }

    private fun combineScores(
        query: String,
        result: FusionCandidate,
        bm25Norm: Double,
        denseNorm: Double,
        recencyNorm: Double
    ): Double {
        var score = wBm25 * bm25Norm + wDense * denseNorm + wRecency * recencyNorm

        if (query.isNotBlank() && result.title.contains(query, ignoreCase = true)) {
            score += wTitle
        }

        score *= when (result.fileType.lowercase()) {
            "pdf", "txt", "md" -> 1.1
            "jpg", "png", "jpeg", "gif", "webp" -> 0.9
            else -> 1.0
        }
        return score
    }

    fun diversify(results: List<FusionCandidate>, lambda: Double = 0.7, limit: Int = 20): List<FusionCandidate> {
        if (results.isEmpty()) return emptyList()

        val selected = mutableListOf<FusionCandidate>()
        val remaining = results.toMutableList()

        selected.add(remaining.removeAt(0))

        while (remaining.isNotEmpty() && selected.size < limit) {
            var bestIndex = 0
            var bestMmr = Double.NEGATIVE_INFINITY

            remaining.forEachIndexed { index, candidate ->
                val relevance = candidate.finalScore
                val maxSim = selected.maxOfOrNull { chosen ->
                    cosineSimilarity(candidate.embedding, chosen.embedding)
                } ?: 0.0

                val mmr = lambda * relevance - (1.0 - lambda) * maxSim
                if (mmr > bestMmr) {
                    bestMmr = mmr
                    bestIndex = index
                }
            }

            selected.add(remaining.removeAt(bestIndex))
        }

        return selected
    }

    private fun calculateRecency(timestamp: Long): Double {
        val ageInDays = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 86_400_000.0
        return exp(-ageInDays / 30.0)
    }

    private fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
