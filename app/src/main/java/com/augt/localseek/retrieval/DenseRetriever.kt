package com.augt.localseek.retrieval

import android.content.Context
import android.util.Log
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.VectorUtils.cosineSimilarity
import com.augt.localseek.ml.VectorUtils.toFloatArray
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DenseRetriever(context: Context) {

    private val dao = AppDatabase.getInstance(context).documentDao()
    
    // We keep the encoder open in memory so we don't have to reload the .tflite file every keystroke
    private val encoder = DenseEncoder(context)

    suspend fun search(query: String, topK: Int = 50): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Convert the user's text query into a 384-dimensional vector
        val queryVector = encoder.encode(query)

        // 2. Fetch all document vectors from the database
        val allDocs = dao.getAllEmbeddings()

        // --- SAFER DEBUG LOGS ---
        Log.d("DenseDebug", "========================================")
        Log.d("DenseDebug", "Query: '$query'")
        Log.d("DenseDebug", "Query Vector (first 5): ${queryVector.take(5).toList()}")
        Log.d("DenseDebug", "Found ${allDocs.size} documents with embeddings.")

        // Only try to log doc vectors if we actually have them
        if (allDocs.isNotEmpty()) {
            val doc0Vector = allDocs[0].embedding.toFloatArray()
            Log.d("DenseDebug", "Doc 0 ('${allDocs[0].title}') Vector: ${doc0Vector.take(5).toList()}")
        }
        if (allDocs.size > 1) {
            val doc1Vector = allDocs[1].embedding.toFloatArray()
            Log.d("DenseDebug", "Doc 1 ('${allDocs[1].title}') Vector: ${doc1Vector.take(5).toList()}")
        }
        Log.d("DenseDebug", "========================================")
        // --- END OF SAFER DEBUG LOGS ---

        if (allDocs.isEmpty()) return@withContext emptyList()

        // 3. Compute Cosine Similarity for every document
        val results = allDocs.mapNotNull { doc ->
            val docVector = doc.embedding.toFloatArray()
            
            // Score will be between -1.0 (completely opposite) and 1.0 (exact match)
            val score = cosineSimilarity(queryVector, docVector)

            // --- THIS IS THE FIX ---
            // Set a semantic relevance threshold. If the AI is less than 30%
            // confident, we consider it noise and throw it away. This prevents
            // random, irrelevant files from polluting your RRF stage.
            if (score < 0.3f) {
                return@mapNotNull null
            }
            // -------------------------

            SearchResult(
                id = doc.id,
                title = doc.title,
                snippet = doc.bodySnippet.trim().replace("\n", " ") + "...",
                filePath = doc.filePath,
                fileType = doc.fileType,
                score = score, // This is the raw cosine similarity
                modifiedAt = doc.modifiedAt
            )
        }

        // 4. Sort by highest similarity and return top results
        // Note: For research papers, a score > 0.3 is usually a "weak semantic match", 
        // and > 0.6 is a "strong semantic match".
        results.sortedByDescending { it.score }.take(topK)
    }

    fun close() {
        encoder.close()
    }
}
