package com.augt.localseek.retrieval

import kotlin.math.exp
import kotlin.math.sqrt

data class FusionCandidate(
    val id: Long,
    val title: String,
    val snippet: String,
    val filePath: String,
    val fileType: String,
    val modifiedAt: Long,
    val bm25Score: Double? = null,
    val denseScore: Double? = null,
    val embedding: FloatArray? = null,
    val finalScore: Double = 0.0
)

class FusionRanker {
    private val wBm25 = 0.45
    private val wDense = 0.35
    private val wRecency = 0.10
    private val wTitle = 0.10

    fun rank(query: String, results: List<FusionCandidate>): List<FusionCandidate> {
        if (results.isEmpty()) return emptyList()

        val bm25Scores = results.map { it.bm25Score ?: 0.0 }
        val denseScores = results.map { it.denseScore ?: 0.0 }
        val recencyScores = results.map { calculateRecency(it.modifiedAt) }

        val bm25Norm = ScoreNormalizer.minMaxNorm(bm25Scores)
        val denseNorm = ScoreNormalizer.minMaxNorm(denseScores)
        val recencyNorm = ScoreNormalizer.minMaxNorm(recencyScores)

        return results.mapIndexed { i, result ->
            var score =
                wBm25 * bm25Norm[i] +
                wDense * denseNorm[i] +
                wRecency * recencyNorm[i]

            if (query.isNotBlank() && result.title.contains(query, ignoreCase = true)) {
                score += wTitle
            }

            score *= when (result.fileType.lowercase()) {
                "pdf", "txt", "md" -> 1.1
                "jpg", "png", "jpeg", "gif", "webp" -> 0.9
                else -> 1.0
            }

            result.copy(finalScore = score)
        }.sortedByDescending { it.finalScore }
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

