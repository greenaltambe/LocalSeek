package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.SearchResult
import kotlin.math.max

class BM25Retriever(context: Context) {

    private val chunkDao = AppDatabase.getInstance(context).chunkDao()

    suspend fun search(rawQuery: String, limit: Int = 50): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        // Sanitize the user input into a format FTS5 understands.
        val ftsQuery = buildFtsQuery(rawQuery)
        if (ftsQuery.isBlank()) return emptyList()
        
        println("FTS Query: $ftsQuery")

        return try {
            val chunkHits = chunkDao.searchChunks(ftsQuery, max(limit * 3, limit))
            if (chunkHits.isEmpty()) return emptyList()

            val aggregated = ChunkAggregator.aggregateChunks(chunkHits)
            if (aggregated.isEmpty()) return emptyList()

            // Normalize the scores to a 0.0-1.0 range for the UI.
            // Raw BM25 scores are negative (e.g., -8.3, -2.1), where a more negative
            // number means a better match. We need to flip and scale them.
            val minScore = aggregated.minOf { it.bestScore }
            val maxScore = aggregated.maxOf { it.bestScore }
            val range = maxScore - minScore

            aggregated.take(limit).map { r ->
                // This formula maps the most relevant item (minScore) to 1.0
                // and the least relevant item (maxScore) to 0.0.
                val normalizedScore = if (range == 0f) 1f
                else (maxScore - r.bestScore) / range

                SearchResult(
                    id = r.parentFileId,
                    title = r.title,
                    snippet = r.relevantChunks.joinToString(" ... "),
                    filePath = r.filePath,
                    fileType = r.fileType,
                    score = normalizedScore,
                    modifiedAt = r.modifiedAt
                )
            }
        } catch (e: Exception) {
            // FTS5 can throw an exception if the query syntax is invalid.
            android.util.Log.e("BM25Retriever", "Search failed for query: $ftsQuery", e)
            emptyList()
        }
    }


    /**
     * Prepares a raw user query for FTS5.
     * Changed to use AND logic and prefix matching (*) to make search much more flexible.
     * Example: "kotlin guide" -> "\"kotlin\"* AND \"guide\"*"
     */
    private fun buildFtsQuery(query: String): String {
        val tokens = query.trim()
            .split("\\s+".toRegex()) // Split on one or more spaces
            .filter { it.isNotBlank() }
            
        if (tokens.isEmpty()) return ""

        // Join terms with AND to ensure all terms must be present, but anywhere in the doc.
        // Add * to the end of each term for prefix matching (e.g. "andro" matches "android").
        return tokens.joinToString(" AND ") { token ->
            // Escape any double quotes within the token.
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }
}
