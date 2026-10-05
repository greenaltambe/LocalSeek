package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import kotlin.math.pow
import kotlin.math.sqrt

object ScoreNormalizer {
    /**
     * Range threshold below which score differences are treated as numerical noise
     * rather than discriminative signal. When (max - min) < EPSILON, all scores
     * are assigned a neutral 0.5 to prevent stretching tiny gaps to [0.0, 1.0].
     */
    const val EPSILON = 1e-9

    fun minMaxNorm(scores: List<Double>): List<Double> {
        if (scores.isEmpty()) return emptyList()

        val min = scores.minOrNull() ?: 0.0
        val max = scores.maxOrNull() ?: 1.0
        val range = max - min

        return if (range >= EPSILON) {
            scores.map { (it - min) / range }
        } else {
            // Neutral score 0.5 for non-discriminative ranges (< EPSILON)
            scores.map { 0.5 }
        }
    }

    fun minMaxNormPerGroup(
        candidates: List<FusionCandidate>,
        scoreSelector: (FusionCandidate) -> Double,
        groupSelector: (FusionCandidate) -> EntityType
    ): Map<Pair<EntityType, Long>, Double> {
        if (candidates.isEmpty()) return emptyMap()

        val groups = candidates.groupBy(groupSelector)
        val result = mutableMapOf<Pair<EntityType, Long>, Double>()

        groups.forEach { (type, groupCandidates) ->
            val scores = groupCandidates.map(scoreSelector)
            val min = scores.minOrNull() ?: 0.0
            val max = scores.maxOrNull() ?: 1.0
            val range = max - min

            groupCandidates.forEachIndexed { index, candidate ->
                val normalized = if (range >= EPSILON) {
                    (scores[index] - min) / range
                } else {
                    // Neutral score 0.5 for non-discriminative ranges (< EPSILON)
                    0.5
                }
                result[Pair(type, candidate.id)] = normalized
            }
        }

        return result
    }

    fun standardize(scores: List<Double>): List<Double> {
        if (scores.isEmpty()) return emptyList()

        val mean = scores.average()
        val variance = scores.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)

        return if (std > 0.0) {
            scores.map { (it - mean) / std }
        } else {
            scores.map { 0.0 }
        }
    }
}
