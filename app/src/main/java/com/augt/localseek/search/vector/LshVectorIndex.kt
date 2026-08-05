package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding

class LshVectorIndex(
    private val manager: LshIndexManager,
    private val chunkDao: ChunkDao
) : VectorIndex {

    override val backendName: String = "lsh"

    override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
        return manager.search(queryVec, k, chunkDao)
    }

    override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {
        val pairs = embeddings.map { it.id to it.embedding }
        manager.buildIndex(pairs)
    }
}
