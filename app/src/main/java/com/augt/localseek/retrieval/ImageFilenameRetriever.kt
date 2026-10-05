package com.augt.localseek.retrieval

import com.augt.localseek.core.IdentityUtils
import com.augt.localseek.data.ImageDao
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max

/**
 * Non-neural lexical BM25 baseline retriever operating over photo metadata
 * (filename / display name / URI path) for benchmark arm I2_filename_bm25_baseline.
 */
class ImageFilenameRetriever(
    private val imageDao: ImageDao,
    private val k1: Double = 1.2,
    private val b: Double = 0.75
) {
    companion object {
        private val TOKEN_REGEX = Regex("[^a-zA-Z0-9]+")

        fun tokenize(text: String): List<String> {
            return text.lowercase()
                .split(TOKEN_REGEX)
                .filter { it.isNotBlank() }
        }
    }

    suspend fun search(query: String, topK: Int = 20): List<SearchResult> = withContext(Dispatchers.IO) {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return@withContext emptyList()

        val allImages = imageDao.getAllImages()
        if (allImages.isEmpty()) return@withContext emptyList()

        val scored = scoreBm25(qTokens, allImages)
        scored.take(topK).map { (img, score) ->
            val stableKey = if (img.stableKey.isNotBlank()) {
                img.stableKey
            } else {
                IdentityUtils.imageStableKey(img.mediaStoreId)
            }
            SearchResult(
                id = img.id,
                title = img.displayName,
                snippet = "Photo: ${img.displayName}",
                filePath = img.uri,
                fileType = img.displayName.substringAfterLast('.', "jpg").lowercase(),
                score = score.toFloat(),
                modifiedAt = img.dateModified,
                embedding = img.embedding,
                entityType = EntityType.IMAGE,
                stableKey = stableKey
            )
        }
    }

    fun scoreBm25(qTokens: List<String>, images: List<ImageEntity>): List<Pair<ImageEntity, Double>> {
        val n = images.size.toDouble()
        val docTokensList = images.map { img ->
            val metaText = "${img.displayName} ${img.uri}"
            tokenize(metaText)
        }

        val totalDocLen = docTokensList.sumOf { it.size }.toDouble()
        val avgDocLen = max(1.0, totalDocLen / n)

        // Document frequency per query token
        val docFreq = mutableMapOf<String, Int>()
        for (token in qTokens.distinct()) {
            docFreq[token] = docTokensList.count { it.contains(token) }
        }

        // IDF calculation
        val idf = mutableMapOf<String, Double>()
        for ((token, df) in docFreq) {
            val num = n - df + 0.5
            val denom = df + 0.5
            idf[token] = max(1e-4, ln(1.0 + (num / denom)))
        }

        val scoredList = mutableListOf<Pair<ImageEntity, Double>>()
        for (i in images.indices) {
            val img = images[i]
            val docTokens = docTokensList[i]
            val docLen = docTokens.size.toDouble()
            if (docLen == 0.0) continue

            // Count term frequencies
            val tfMap = mutableMapOf<String, Int>()
            for (token in docTokens) {
                tfMap[token] = (tfMap[token] ?: 0) + 1
            }

            var docScore = 0.0
            for (qToken in qTokens) {
                val tf = tfMap[qToken] ?: 0
                if (tf > 0) {
                    val tokenWeight = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDocLen)))
                    docScore += (idf[qToken] ?: 0.0) * tokenWeight
                }
            }

            if (docScore > 0.0) {
                scoredList.add(Pair(img, docScore))
            }
        }

        return scoredList.sortedWith(
            compareByDescending<Pair<ImageEntity, Double>> { it.second }
                .thenBy { it.first.displayName }
                .thenBy { it.first.id }
        )
    }
}
