package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkEmbedding
import com.augt.localseek.data.ImageDao
import com.augt.localseek.ml.VectorUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.PriorityQueue

/**
 * Brute-force vector index implementation for device photos (512-dim CLIP embeddings).
 *
 * Scans ImageDao using keyset pagination and computes exact cosine similarity.
 * Designed for small/compact image corpora (< 5,000 photos) with zero projection loss.
 */
class ImageBruteForceVectorIndex(
    private val imageDao: ImageDao
) : VectorIndex {

    override val backendName: String = "image_brute_force"

    override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
        val topKQueue = PriorityQueue(compareBy<ScoredResult> { it.score })
        var lastId = -1L
        val pageSize = 500

        while (true) {
            currentCoroutineContext().ensureActive()

            val page = imageDao.getEmbeddingsPage(pageSize, lastId)
            if (page.isEmpty()) break

            for (img in page) {
                val score = VectorUtils.cosineSimilarity(queryVec, img.embedding)
                if (topKQueue.size < k) {
                    topKQueue.add(ScoredResult(img.id, score))
                } else {
                    val smallest = topKQueue.peek()
                    if (smallest != null && score > smallest.score) {
                        topKQueue.poll()
                        topKQueue.add(ScoredResult(img.id, score))
                    }
                }
                lastId = img.id
            }
        }

        return topKQueue.toList().sortedWith(
            compareByDescending<ScoredResult> { it.score }
                .thenBy { it.id }
        )
    }

    override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {
        // Database is index for brute-force vector search
    }
}
