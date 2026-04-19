package com.augt.localseek.retrieval

import kotlin.math.pow
import kotlin.math.sqrt

object ScoreNormalizer {
    fun minMaxNorm(scores: List<Double>): List<Double> {
        if (scores.isEmpty()) return emptyList()

        val min = scores.minOrNull() ?: 0.0
        val max = scores.maxOrNull() ?: 1.0
        val range = max - min

        return if (range > 0.0) {
            scores.map { (it - min) / range }
        } else {
            scores.map { 0.5 }
        }
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

