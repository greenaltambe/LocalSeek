package com.augt.localseek.retrieval

import android.content.Context
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.VectorUtils.cosineSimilarity
import com.augt.localseek.model.SearchResult
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DenseRetriever(context: Context) {

    private val chunkDao = AppDatabase.getInstance(context).chunkDao()
    private val encoder = DenseEncoder(context)

    private data class ScoredChunk(val chunkId: Long, val score: Float)

    suspend fun search(query: String, topK: Int = 50, pageSize: Int = 500): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val queryVector = encoder.encode(query)
        val threshold = 0.3f
        val boundedTopK = topK.coerceAtLeast(1)
        val pageLimit = pageSize.coerceAtLeast(1)

        val topChunks = PriorityQueue(compareBy<ScoredChunk> { it.score })
        var offset = 0
        var totalScored = 0
        var totalKept = 0

        while (true) {
            val page = chunkDao.getEmbeddingsPage(limit = pageLimit, offset = offset)
            if (page.isEmpty()) break

            page.forEach { chunk ->
                totalScored++
                val score = cosineSimilarity(queryVector, chunk.embedding)
                if (score < threshold) return@forEach

                totalKept++
                if (topChunks.size < boundedTopK) {
                    topChunks.add(ScoredChunk(chunk.id, score))
                } else {
                    val smallest = topChunks.peek()
                    if (smallest != null && score > smallest.score) {
                        topChunks.poll()
                        topChunks.add(ScoredChunk(chunk.id, score))
                    }
                }
            }

            offset += pageLimit
        }

        Log.d("DenseRetriever", "Streaming dense scored=$totalScored kept=$totalKept top=${topChunks.size}")

        if (topChunks.isEmpty()) return@withContext emptyList()

        val scoredChunks = topChunks.toList().sortedByDescending { it.score }
        val scoreByChunkId = scoredChunks.associate { it.chunkId to it.score }
        val metadata = chunkDao.getChunkMetadataByIds(scoredChunks.map { it.chunkId })
        if (metadata.isEmpty()) return@withContext emptyList()

        metadata
            .groupBy { it.parentFileId }
            .map { (_, rows) ->
                val bestRow = rows.maxBy { scoreByChunkId[it.chunkId] ?: Float.NEGATIVE_INFINITY }
                val bestScore = scoreByChunkId[bestRow.chunkId] ?: 0f
                val snippets = rows
                    .sortedByDescending { scoreByChunkId[it.chunkId] ?: Float.NEGATIVE_INFINITY }
                    .take(3)
                    .joinToString(" ... ") { it.text.take(200) }

                SearchResult(
                    id = bestRow.parentFileId,
                    title = bestRow.title,
                    snippet = snippets,
                    filePath = bestRow.filePath,
                    fileType = bestRow.fileType,
                    score = bestScore,
                    modifiedAt = bestRow.modifiedAt
                )
            }
            .sortedByDescending { it.score }
            .take(boundedTopK)
    }

    fun close() {
        encoder.close()
    }
}
