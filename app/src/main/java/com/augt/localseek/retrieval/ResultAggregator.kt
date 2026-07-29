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
    val entityType: EntityType = EntityType.FILE
)

object ResultAggregator {
    fun aggregateToFiles(chunks: List<SearchResult>, query: String): List<FileResult> {
        if (chunks.isEmpty()) return emptyList()

        val fileChunks = chunks.filter { it.entityType == EntityType.FILE }
        val otherEntities = chunks.filter { it.entityType != EntityType.FILE }

        val aggregatedFiles = fileChunks
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
                    sizeBytes = first.sizeBytes,
                    entityType = EntityType.FILE
                )
            }

        val formattedOthers = otherEntities.map {
            FileResult(
                id = it.id,
                filePath = it.filePath,
                title = it.title,
                fileType = it.fileType,
                bestScore = it.score.toDouble(),
                snippets = listOf(highlightQuery(it.snippet, query)),
                modifiedAt = it.modifiedAt,
                sizeBytes = it.sizeBytes,
                entityType = it.entityType
            )
        }

        return (aggregatedFiles + formattedOthers).sortedByDescending { it.bestScore }
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

