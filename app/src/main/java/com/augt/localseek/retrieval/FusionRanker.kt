package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import kotlin.math.exp
import kotlin.math.sqrt

enum class FusionMode {
    GLOBAL_NORMALIZATION,
    PER_TYPE_NORMALIZATION,
    PER_TYPE_WITH_THRESHOLD,
    RRF
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
    val entityType: EntityType = EntityType.FILE,
    val stableKey: String = ""
)

class FusionRanker {
    private val wBm25 = 0.45
    private val wDense = 0.35
    private val wRecency = 0.10
    private val wTitle = 0.10

    companion object {
        const val DENSE_THRESHOLD_FLOOR = 0.35
        const val BM25_THRESHOLD_FRACTION = 0.5
        const val RRF_K = 60
    }

    fun rank(
        query: String,
        results: List<FusionCandidate>,
        mode: FusionMode = FusionMode.GLOBAL_NORMALIZATION,
        typePriorEnabled: Boolean = false,
        perSpaceNorm: Boolean = true,
        referenceTime: Long = System.currentTimeMillis()
    ): List<FusionCandidate> {
        if (results.isEmpty()) return emptyList()

        return when (mode) {
            FusionMode.GLOBAL_NORMALIZATION -> rankGlobal(query, results, typePriorEnabled, perSpaceNorm, referenceTime)
            FusionMode.PER_TYPE_NORMALIZATION -> rankPerType(query, results, typePriorEnabled, referenceTime)
            FusionMode.PER_TYPE_WITH_THRESHOLD -> rankPerTypeWithThreshold(query, results, typePriorEnabled, referenceTime)
            FusionMode.RRF -> rankRrf(results, RRF_K, query)
        }
    }

    private fun rankGlobal(
        query: String,
        results: List<FusionCandidate>,
        typePriorEnabled: Boolean,
        perSpaceNorm: Boolean,
        referenceTime: Long
    ): List<FusionCandidate> {
        val bm25Scores = results.map { it.bm25Score ?: 0.0 }
        val denseScores = results.map { it.denseScore ?: 0.0 }

        val bm25Norm = ScoreNormalizer.minMaxNorm(bm25Scores)
        val denseNorm = if (perSpaceNorm) {
            // Separate MiniLM (FILE, APP, CONTACT) from CLIP (IMAGE) to prevent cross-space pooling
            val textIndices = results.indices.filter { results[it].entityType != EntityType.IMAGE }
            val imageIndices = results.indices.filter { results[it].entityType == EntityType.IMAGE }

            val textScores = textIndices.map { denseScores[it] }
            val imageScores = imageIndices.map { denseScores[it] }

            val textNorm = ScoreNormalizer.minMaxNorm(textScores)
            val imageNorm = ScoreNormalizer.minMaxNorm(imageScores)

            val out = DoubleArray(results.size)
            textIndices.forEachIndexed { i, origIdx -> out[origIdx] = textNorm[i] }
            imageIndices.forEachIndexed { i, origIdx -> out[origIdx] = imageNorm[i] }
            out.toList()
        } else {
            ScoreNormalizer.minMaxNorm(denseScores)
        }

        // Only FILE and IMAGE possess genuine modification timestamps; for APP and CONTACT,
        // modifiedAt represents index time, so their recency contribution is set to 0.0.
        val temporalIndices = results.indices.filter {
            results[it].entityType == EntityType.FILE || results[it].entityType == EntityType.IMAGE
        }
        val recencyNorm = DoubleArray(results.size)
        if (temporalIndices.isNotEmpty()) {
            val temporalScores = temporalIndices.map { calculateRecency(results[it].modifiedAt, referenceTime) }
            val temporalNorm = ScoreNormalizer.minMaxNorm(temporalScores)
            temporalIndices.forEachIndexed { i, origIdx ->
                recencyNorm[origIdx] = temporalNorm[i]
            }
        }

        return results.mapIndexed { i, result ->
            val score = combineScores(query, result, bm25Norm[i], denseNorm[i], recencyNorm[i], typePriorEnabled)
            result.copy(finalScore = score)
        }.sortedWith(
            compareByDescending<FusionCandidate> { it.finalScore }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
    }

    private fun rankPerType(
        query: String,
        results: List<FusionCandidate>,
        typePriorEnabled: Boolean,
        referenceTime: Long
    ): List<FusionCandidate> {
        val bm25NormMap = ScoreNormalizer.minMaxNormPerGroup(results, { it.bm25Score ?: 0.0 }, { it.entityType })
        val denseNormMap = ScoreNormalizer.minMaxNormPerGroup(results, { it.denseScore ?: 0.0 }, { it.entityType })
        val recencyNormMap = ScoreNormalizer.minMaxNormPerGroup(
            results.filter { it.entityType == EntityType.FILE || it.entityType == EntityType.IMAGE },
            { calculateRecency(it.modifiedAt, referenceTime) },
            { it.entityType }
        )

        return results.map { result ->
            val key = Pair(result.entityType, result.id)
            val bm25Norm = bm25NormMap[key] ?: 0.5
            val denseNorm = denseNormMap[key] ?: 0.5
            val recencyNorm = recencyNormMap[key] ?: 0.0

            val score = combineScores(query, result, bm25Norm, denseNorm, recencyNorm, typePriorEnabled)
            result.copy(finalScore = score)
        }.sortedWith(
            compareByDescending<FusionCandidate> { it.finalScore }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
    }

    private fun rankPerTypeWithThreshold(
        query: String,
        results: List<FusionCandidate>,
        typePriorEnabled: Boolean,
        referenceTime: Long
    ): List<FusionCandidate> {
        // TODO(Audit Remediation - E6): The threshold arm computes bm25Floor as topBm25 * BM25_THRESHOLD_FRACTION (0.5).
        // In the end-to-end SearchEngine pipeline, BM25Retriever pre-normalizes BM25 scores to [0.0, 1.0] with the top
        // hit assigned 1.0. As a result, topBm25 is almost always 1.0, fixing bm25Floor at 0.5 regardless of query confidence.
        // Proposed fix:
        // 1. Preserve raw BM25 scores from SQLite FTS5 (e.g., in a dedicated rawScore field) or define absolute raw score
        //    cutoffs per FTS table before min-max normalization.
        // 2. Calibrate thresholds independently for chunks_fts, apps_fts, and contacts_fts, since raw FTS5 IDF values
        //    and column weights differ across the three separate tables.
        val topBm25 = results.maxOfOrNull { it.bm25Score ?: 0.0 } ?: 0.0
        val bm25Floor = topBm25 * BM25_THRESHOLD_FRACTION

        val validGroups = results.groupBy { it.entityType }.filter { (type, candidates) ->
            val bestBm25 = candidates.maxOfOrNull { it.bm25Score ?: 0.0 } ?: 0.0
            val bestDense = candidates.maxOfOrNull { it.denseScore ?: 0.0 } ?: 0.0
            val denseFloor = if (type == EntityType.IMAGE) 0.25 else DENSE_THRESHOLD_FLOOR
            
            bestBm25 >= bm25Floor || bestDense >= denseFloor
        }.keys

        val filteredResults = results.filter { it.entityType in validGroups }
        if (filteredResults.isEmpty()) return emptyList()

        return rankPerType(query, filteredResults, typePriorEnabled, referenceTime)
    }

    /**
     * Reciprocal Rank Fusion (RRF) across available retrieval spaces.
     * Score(d) = sum_{r in rankers} 1 / (k + rank_r(d))
     * Invariant to monotonic score transformations within each retriever space.
     */
    fun rankRrf(
        results: List<FusionCandidate>,
        k: Int = RRF_K,
        query: String = ""
    ): List<FusionCandidate> {
        if (results.isEmpty()) return emptyList()

        // 1. BM25 rank list
        val bm25Ranked = results.filter { it.bm25Score != null }.sortedWith(
            compareByDescending<FusionCandidate> { it.bm25Score }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
        val bm25Ranks = bm25Ranked.mapIndexed { index, c -> (c.entityType to c.id) to (index + 1) }.toMap()

        // 2. MiniLM dense rank list (FILE, APP, CONTACT)
        val textDenseRanked = results.filter { it.entityType != EntityType.IMAGE && it.denseScore != null }.sortedWith(
            compareByDescending<FusionCandidate> { it.denseScore }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
        val textDenseRanks = textDenseRanked.mapIndexed { index, c -> (c.entityType to c.id) to (index + 1) }.toMap()

        // 3. CLIP image dense rank list (IMAGE)
        val imageDenseRanked = results.filter { it.entityType == EntityType.IMAGE && it.denseScore != null }.sortedWith(
            compareByDescending<FusionCandidate> { it.denseScore }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
        val imageDenseRanks = imageDenseRanked.mapIndexed { index, c -> (c.entityType to c.id) to (index + 1) }.toMap()

        // 4. Title match channel in RRF: candidates matching query in title form an additional rank list
        val titleRanks = if (query.isNotBlank()) {
            results.filter { it.title.contains(query, ignoreCase = true) }
                .mapIndexed { index, c -> (c.entityType to c.id) to (index + 1) }
                .toMap()
        } else {
            emptyMap()
        }

        return results.map { candidate ->
            val key = candidate.entityType to candidate.id
            var rrfScore = 0.0

            bm25Ranks[key]?.let { rank ->
                rrfScore += 1.0 / (k + rank)
            }
            textDenseRanks[key]?.let { rank ->
                rrfScore += 1.0 / (k + rank)
            }
            imageDenseRanks[key]?.let { rank ->
                rrfScore += 1.0 / (k + rank)
            }
            titleRanks[key]?.let { rank ->
                rrfScore += 1.0 / (k + rank)
            }

            candidate.copy(finalScore = rrfScore)
        }.sortedWith(
            compareByDescending<FusionCandidate> { it.finalScore }
                .thenBy { it.stableKey }
                .thenBy { it.id }
        )
    }

    private fun combineScores(
        query: String,
        result: FusionCandidate,
        bm25Norm: Double,
        denseNorm: Double,
        recencyNorm: Double,
        typePriorEnabled: Boolean
    ): Double {
        val recencyContribution = if (result.entityType == EntityType.APP || result.entityType == EntityType.CONTACT) {
            0.0
        } else {
            wRecency * recencyNorm
        }
        var score = wBm25 * bm25Norm + wDense * denseNorm + recencyContribution

        if (query.isNotBlank() && result.title.contains(query, ignoreCase = true)) {
            score += wTitle
        }

        if (typePriorEnabled) {
            score *= when (result.fileType.lowercase()) {
                "pdf", "txt", "md" -> 1.1
                "jpg", "png", "jpeg", "gif", "webp" -> 0.9
                else -> 1.0
            }
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
                } else if (mmr == bestMmr) {
                    // Deterministic tie-breaking on stableKey and id
                    val currentBest = remaining[bestIndex]
                    if (candidate.stableKey < currentBest.stableKey ||
                        (candidate.stableKey == currentBest.stableKey && candidate.id < currentBest.id)) {
                        bestIndex = index
                    }
                }
            }

            selected.add(remaining.removeAt(bestIndex))
        }

        return selected
    }

    internal fun calculateRecency(timestamp: Long, referenceTime: Long = System.currentTimeMillis()): Double {
        val ageInDays = (referenceTime - timestamp).coerceAtLeast(0L) / 86_400_000.0
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
