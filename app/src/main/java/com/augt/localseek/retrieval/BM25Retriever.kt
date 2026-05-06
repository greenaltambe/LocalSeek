package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.SearchResult
import kotlin.math.max

class BM25Retriever(context: Context) {

    private val chunkDao = AppDatabase.getInstance(context).chunkDao()
    private val minPreferredHits = 3

    suspend fun search(rawQuery: String, limit: Int = 50): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        return try {
            val tokens = tokenize(rawQuery)
            if (tokens.isEmpty()) return emptyList()

            val andQuery = buildFtsQuery(tokens, useAnd = true)
            val andHits = chunkDao.searchChunks(andQuery, max(limit * 3, limit))

            val chunkHits = when {
                andHits.size >= minPreferredHits || tokens.size == 1 -> andHits
                else -> {
                    val orQuery = buildFtsQuery(tokens, useAnd = false)
                    val orHits = chunkDao.searchChunks(orQuery, max(limit * 3, limit))
                    if (orHits.size >= minPreferredHits) {
                        orHits
                    } else {
                        // Last-recall fallback: union top hits from each term.
                        val union = linkedMapOf<Long, com.augt.localseek.data.ChunkWithMetadata>()
                        val perTermLimit = max(10, limit)
                        tokens.forEach { term ->
                            val termQuery = buildFtsQuery(listOf(term), useAnd = true)
                            chunkDao.searchChunks(termQuery, perTermLimit).forEach { hit ->
                                union.putIfAbsent(hit.chunkId, hit)
                            }
                        }
                        union.values.toList()
                    }
                }
            }

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
                    modifiedAt = r.modifiedAt,
                    sizeBytes = r.sizeBytes
                )
            }
        } catch (e: Exception) {
            // FTS5 can throw an exception if the query syntax is invalid.
            android.util.Log.e("BM25Retriever", "Search failed for query: $rawQuery", e)
            emptyList()
        }
    }


    /**
     * Prepares a raw user query for FTS5.
     * Changed to use AND logic and prefix matching (*) to make search much more flexible.
     * Example: "kotlin guide" -> "\"kotlin\"* AND \"guide\"*"
     */
    private fun tokenize(query: String): List<String> {
        return query.trim()
            .split("\\s+".toRegex()) // Split on one or more spaces
            .filter { it.isNotBlank() }
    }

    private fun buildFtsQuery(tokens: List<String>, useAnd: Boolean): String {
        if (tokens.isEmpty()) return ""
        val operator = if (useAnd) " AND " else " OR "

        return tokens.joinToString(operator) { token ->
            // Escape any double quotes within the token.
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }
}
