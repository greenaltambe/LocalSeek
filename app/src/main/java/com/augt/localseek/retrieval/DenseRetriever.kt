package com.augt.localseek.retrieval

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.model.SearchResult

class DenseRetriever(context: Context, private val encoder: DenseEncoder) {

    private val dao = AppDatabase.getInstance(context).documentDao()

    suspend fun search(query: String, topK: Int = 15): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // 1. Convert the user's text query into a 384-dimensional vector
        val queryVector = encoder.encode(query)

        // 2. Fetch all document vectors (chunks) from SQLite
        val allDocs = dao.getAllVectors()
        if (allDocs.isEmpty()) return emptyList()

        // 3. Calculate Dot Product (Cosine Similarity) for every chunk
        val scoredChunks = allDocs.map { doc ->
            doc to dotProduct(queryVector, doc.embedding)
        }

        // 4. AGGREGATION: For each unique file, keep only the best-scoring chunk
        val bestResultsPerFile = scoredChunks
            .groupBy { it.first.filePath }
            .map { (_, chunks) ->
                // Pick the chunk with the highest score for this specific file
                chunks.maxByOrNull { it.second }!!
            }

        // 5. Sort by highest score and take the Top K results
        return bestResultsPerFile
            .sortedByDescending { it.second }
            .take(topK)
            .map { (doc, score) ->
                SearchResult(
                    id = doc.id,
                    title = doc.title,
                    // Use the chunk text as the snippet (first 150 chars)
                    snippet = doc.body.take(150).replace("\n", " ") + "...",
                    filePath = doc.filePath,
                    fileType = doc.fileType,
                    score = score,
                    modifiedAt = doc.modifiedAt
                )
            }
    }

    /**
     * Because our vectors are L2-Normalized, Cosine Similarity simplifies to Dot Product.
     */
    private fun dotProduct(v1: FloatArray, v2: FloatArray): Float {
        var sum = 0f
        for (i in v1.indices) {
            sum += v1[i] * v2[i]
        }
        return sum
    }
}
