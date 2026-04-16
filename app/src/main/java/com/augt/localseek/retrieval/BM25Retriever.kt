package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.model.SearchResult

class BM25Retriever(context: Context) {

    // Get an instance of our database DAO.
    private val dao = AppDatabase.getInstance(context).documentDao()

    suspend fun search(rawQuery: String, limit: Int = 50): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()

        // Sanitize the user input into a format FTS5 understands.
        val ftsQuery = buildFtsQuery(rawQuery)
        if (ftsQuery.isBlank()) return emptyList()
        
        println("FTS Query: $ftsQuery")

        return try {
            // Passing the limit down to the DAO (needs to be updated there too)
            val rawResults = dao.searchBm25(ftsQuery, limit)
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
            // FTS5 can throw an exception if the query syntax is invalid.
            android.util.Log.e("BM25Retriever", "Search failed for query: $ftsQuery", e)
            emptyList()
        }
    }

    /**
     * Extracts a ~150 character snippet from the body, centered on the first
     * matching query term. This is for the preview text in the UI.
     */
    private fun extractSnippet(body: String, query: String): String {
        val firstTerm = query.trim().split("\\s+".toRegex()).firstOrNull() ?: return body.take(150)
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
