package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkEmbedding

data class ScoredResult(
    val id: Long,
    val score: Float
)

interface VectorIndex {
    suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult>
    suspend fun buildIndex(embeddings: List<ChunkEmbedding>)
    val backendName: String
}
