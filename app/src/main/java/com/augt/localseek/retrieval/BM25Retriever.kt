package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.SearchResult

class BM25Retriever(context: Context) {

    // Get an instance of our database DAO.
    private val dao = AppDatabase.getInstance(context).documentDao()

    suspend fun search(rawQuery: String): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        // Sanitize the user input into a format FTS5 understands.
        val ftsQuery = buildFtsQuery(rawQuery)
        println("Query: $ftsQuery")


        return try {
            val rawResults = dao.searchBm25(ftsQuery)
            if (rawResults.isEmpty()) return emptyList()

            // Normalize the scores to a 0.0-1.0 range for the UI.
            // Raw BM25 scores are negative (e.g., -8.3, -2.1), where a more negative
            // number means a better match. We need to flip and scale them.
            val minScore = rawResults.minOf { it.score } // Most relevant (e.g., -10.5)
            val maxScore = rawResults.maxOf { it.score } // Least relevant (e.g., -1.2)
            val range = maxScore - minScore

            rawResults.map { r ->
                // This formula maps the most relevant item (minScore) to 1.0
                // and the least relevant item (maxScore) to 0.0.
                val normalizedScore = if (range == 0f) 1f
                else (maxScore - r.score) / range

                SearchResult(
                    id = r.id,
                    title = r.title,
                    snippet = extractSnippet(r.body, rawQuery), // Create a preview snippet
                    filePath = r.filePath,
                    fileType = r.fileType,
                    score = normalizedScore,
                    modifiedAt = r.modifiedAt
                )
            }
        } catch (e: Exception) {
            // FTS5 can throw an exception if the query syntax is invalid,
            // e.g., if the user types a single quote or asterisk.
            // We'll just return an empty list instead of crashing the app.
            emptyList()
        }
    }

    /**
     * Extracts a ~150 character snippet from the body, centered on the first
     * matching query term. This is for the preview text in the UI.
     */
    private fun extractSnippet(body: String, query: String): String {
        val firstTerm = query.trim().split(" ").firstOrNull() ?: return body.take(150)
        val idx = body.indexOf(firstTerm, ignoreCase = true)
        return if (idx < 0) {
            body.take(150)
        } else {
            val start = maxOf(0, idx - 60)
            val end = minOf(body.length, idx + 90)
            (if (start > 0) "…" else "") + body.substring(start, end) + (if (end < body.length) "…" else "")
        }
    }

    /**
     * Prepares a raw user query for FTS5. This wraps each term in quotes
     * to handle special characters and improve matching.
     * Example: "on-device search" -> "\"on-device\" \"search\""
     */
    private fun buildFtsQuery(query: String): String {
        return query.trim()
            .split("\\s+".toRegex()) // Split on one or more spaces
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                // Escape any double quotes within the token itself before wrapping.
                val escaped = token.replace("\"", "\"\"")
                "\"$escaped\""
            }
    }
}