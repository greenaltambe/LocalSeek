package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult

data class FileResult(
    val id: Long,
    val filePath: String,
    val title: String,
    val fileType: String,
    val bestScore: Double,
    val snippets: List<String>,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val entityType: EntityType = EntityType.FILE,
    val stableKey: String = ""
)

object ResultAggregator {
    fun aggregateToFiles(chunks: List<SearchResult>, query: String): List<FileResult> {
        if (chunks.isEmpty()) return emptyList()

        return chunks
            .groupBy { it.entityType to (if (it.stableKey.isNotBlank()) it.stableKey else it.filePath) }
            .values
            .map { group ->
                val topChunks = group.sortedWith(
                    compareByDescending<SearchResult> { it.score }
                        .thenBy { it.stableKey }
                        .thenBy { it.id }
                )
                val first = topChunks.first()

                FileResult(
                    id = first.id,
                    filePath = first.filePath,
                    title = first.title,
                    fileType = first.fileType,
                    bestScore = topChunks.maxOf { it.score.toDouble() },
                    snippets = if (first.entityType == EntityType.FILE) {
                        topChunks.take(3).map { highlightQuery(it.snippet, query) }
                    } else {
                        listOf(highlightQuery(first.snippet, query))
                    },
                    modifiedAt = first.modifiedAt,
                    sizeBytes = first.sizeBytes,
                    entityType = first.entityType,
                    stableKey = first.stableKey
                )
            }
            .sortedWith(
                compareByDescending<FileResult> { it.bestScore }
                    .thenBy { it.stableKey }
                    .thenBy { it.id }
            )
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
