package com.augt.localseek.retrieval

import com.augt.localseek.data.ChunkWithMetadata

data class FileResult(
    val parentFileId: Long,
    val filePath: String,
    val title: String,
    val bestScore: Float,
    val relevantChunks: List<String>,
    val fileType: String,
    val modifiedAt: Long
)

object ChunkAggregator {
    fun aggregateChunks(chunks: List<ChunkWithMetadata>): List<FileResult> {
        if (chunks.isEmpty()) return emptyList()

        return chunks
            .groupBy { it.parentFileId }
            .values
            .map { fileChunks ->
                val sortedByRelevance = fileChunks.sortedBy { it.score }
                val bestChunk = sortedByRelevance.first()

                FileResult(
                    parentFileId = bestChunk.parentFileId,
                    filePath = bestChunk.filePath,
                    title = bestChunk.title,
                    bestScore = bestChunk.score,
                    relevantChunks = sortedByRelevance.take(3).map { it.text.take(200) },
                    fileType = bestChunk.fileType,
                    modifiedAt = bestChunk.modifiedAt
                )
            }
            .sortedBy { it.bestScore }
    }
}

