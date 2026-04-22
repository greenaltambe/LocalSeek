package com.augt.localseek.search.rag

import android.content.Context
import android.util.Log
import com.augt.localseek.ml.llm.LLMProvider
import com.augt.localseek.ml.llm.OnDeviceLLM
import com.augt.localseek.retrieval.FileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RAGEngine(private val context: Context) {

    companion object {
        private const val TAG = "RAGEngine"
        private const val MAX_CONTEXT_CHUNKS = 5
        private const val MAX_CONTEXT_LENGTH = 2000
    }

    private val llmProvider = LLMProvider(context)
    private var llm: OnDeviceLLM? = null
    private var isInitialized: Boolean = false

    suspend fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "RAG already initialized")
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== RAG Engine Initialization ===")
                llm = llmProvider.getAvailableLLM()
                if (llm != null) {
                    val capabilities = llmProvider.getCapabilities()
                    Log.d(TAG, "RAG ready with ${capabilities.name} (${capabilities.provider})")
                    isInitialized = true
                    true
                } else {
                    Log.w(TAG, "No LLM available; RAG disabled")
                    isInitialized = false
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAG initialization failed", e)
                isInitialized = false
                false
            }
        }
    }

    suspend fun generateAnswer(query: String, searchResults: List<FileResult>): RAGResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            val activeLlm = llm
            if (!isInitialized || activeLlm == null) {
                return@withContext RAGResult(
                    answer = null,
                    error = "AI answers are not available on this device",
                    searchResults = searchResults,
                    totalLatencyMs = System.currentTimeMillis() - startTime
                )
            }

            val contextChunks = extractContext(searchResults)
            if (contextChunks.isEmpty()) {
                return@withContext RAGResult(
                    answer = null,
                    error = "No relevant context found in search results",
                    searchResults = searchResults,
                    totalLatencyMs = System.currentTimeMillis() - startTime
                )
            }

            val response = activeLlm.generateAnswer(contextChunks, query)
            val citations = extractCitations(searchResults)
            val totalLatency = System.currentTimeMillis() - startTime

            RAGResult(
                answer = response.answer.takeIf { response.error == null && it.isNotBlank() },
                error = response.error,
                searchResults = searchResults,
                citations = citations,
                llmLatencyMs = response.latencyMs,
                totalLatencyMs = totalLatency
            )
        } catch (e: Exception) {
            Log.e(TAG, "RAG generation failed", e)
            RAGResult(
                answer = null,
                error = "Answer generation failed: ${e.message}",
                searchResults = searchResults,
                totalLatencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    fun isAvailable(): Boolean = isInitialized && llm != null

    private fun extractContext(results: List<FileResult>): List<String> {
        val chunks = mutableListOf<String>()
        var totalLength = 0
        for (result in results) {
            for (snippet in result.snippets) {
                if (chunks.size >= MAX_CONTEXT_CHUNKS) break
                if (totalLength + snippet.length > MAX_CONTEXT_LENGTH) continue
                chunks.add(snippet)
                totalLength += snippet.length
            }
            if (chunks.size >= MAX_CONTEXT_CHUNKS) break
        }
        return chunks
    }

    private fun extractCitations(results: List<FileResult>): List<String> {
        return results.take(3).map { it.filePath }
    }
}

data class RAGResult(
    val answer: String?,
    val error: String? = null,
    val searchResults: List<FileResult>,
    val citations: List<String> = emptyList(),
    val llmLatencyMs: Long = 0L,
    val totalLatencyMs: Long
)
