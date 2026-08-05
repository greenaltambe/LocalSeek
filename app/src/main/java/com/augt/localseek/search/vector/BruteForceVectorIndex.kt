package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import com.augt.localseek.ml.VectorUtils
import java.util.PriorityQueue

class BruteForceVectorIndex(
    private val chunkDao: ChunkDao
) : VectorIndex {

    override val backendName: String = "brute_force"

    override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
        val topKQueue = PriorityQueue(compareBy<ScoredResult> { it.score })
        var offset = 0
        val pageSize = 500

        while (true) {
            val page = chunkDao.getEmbeddingsPage(pageSize, offset)
            if (page.isEmpty()) break

            for (chunk in page) {
                val score = VectorUtils.cosineSimilarity(queryVec, chunk.embedding)
                if (topKQueue.size < k) {
                    topKQueue.add(ScoredResult(chunk.id, score))
                } else {
                    val smallest = topKQueue.peek()
                    if (smallest != null && score > smallest.score) {
                        topKQueue.poll()
                        topKQueue.add(ScoredResult(chunk.id, score))
                    }
                }
            }
            offset += pageSize
        }

        return topKQueue.toList().sortedByDescending { it.score }
    }

    override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {
        // Brute force uses the database as its index; nothing extra to build here.
    }
}
