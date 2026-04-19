package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult

data class FileResult(
    val id: Long,
    val filePath: String,
    val title: String,
    val fileType: String,
    val bestScore: Double,
    val snippets: List<String>,
    val modifiedAt: Long,
    val sizeBytes: Long
)

object ResultAggregator {
    fun aggregateToFiles(chunks: List<SearchResult>, query: String): List<FileResult> {
        if (chunks.isEmpty()) return emptyList()

        return chunks
            .groupBy { it.filePath }
            .values
            .map { chunkGroup ->
                val topChunks = chunkGroup.sortedByDescending { it.score }
                val first = topChunks.first()

                FileResult(
                    id = first.id,
                    filePath = first.filePath,
                    title = first.title,
                    fileType = first.fileType,
                    bestScore = topChunks.maxOf { it.score.toDouble() },
                    snippets = topChunks
                        .take(3)
                        .map { highlightQuery(it.snippet, query) },
                    modifiedAt = first.modifiedAt,
                    sizeBytes = first.sizeBytes
                )
            }
            .sortedByDescending { it.bestScore }
    }

    private fun highlightQuery(text: String, query: String): String {
        val terms = query.split("\\s+".toRegex()).filter { it.isNotBlank() }
        var highlighted = text.take(200)

        terms.forEach { term ->
            highlighted = highlighted.replace(term, "**$term**", ignoreCase = true)
        }

        return "$highlighted..."
    }
}

