package com.augt.localseek.search.rag

import android.content.Context
import android.util.Log
import com.augt.localseek.ml.llm.LLMProvider
import com.augt.localseek.ml.llm.OnDeviceLLM
import com.augt.localseek.retrieval.FileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RAGEngine(private val context: Context) {

    companion object {
        private const val TAG = "RAGEngine"
        private const val MAX_CONTEXT_CHUNKS = 5
        private const val MAX_CONTEXT_LENGTH = 2000
    }

    private val llmProvider = LLMProvider(context)
    private var llm: OnDeviceLLM? = null
    private var isInitialized: Boolean = false
    private val initMutex = Mutex()
    private var initInProgress = false

    suspend fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "RAG already initialized, skipping")
            return true
        }

        if (initInProgress) {
            Log.d(TAG, "RAG initialization already in progress, waiting...")
            initMutex.withLock { /* wait for ongoing init */ }
            return isInitialized
        }

        return initMutex.withLock {
            initInProgress = true
            try {
                withContext(Dispatchers.IO) {
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
            } finally {
                initInProgress = false
            }
        }
    }

    suspend fun generateAnswer(query: String, searchResults: List<FileResult>): RAGResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            val activeLlm = llm
            Log.d(TAG, "generateAnswer called, llm=$activeLlm, initialized=$isInitialized")
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

            val result = RAGResult(
                answer = response.answer.takeIf { response.error == null && it.isNotBlank() },
                error = response.error,
                searchResults = searchResults,
                citations = citations,
                llmLatencyMs = response.latencyMs,
                totalLatencyMs = totalLatency
            )
            Log.d(TAG, "generateAnswer result: answer=${result.answer?.take(50)}, error=${result.error}")
            result
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

                val availableSpace = MAX_CONTEXT_LENGTH - totalLength
                if (availableSpace <= 0) break

                val chunk = if (snippet.length > availableSpace) {
                    snippet.take(availableSpace)
                } else {
                    snippet
                }

                chunks.add(chunk)
                totalLength += chunk.length
            }
            if (chunks.size >= MAX_CONTEXT_CHUNKS || totalLength >= MAX_CONTEXT_LENGTH) break
        }
        Log.d(TAG, "extractContext: selected ${chunks.size} chunks, total $totalLength chars from ${results.size} results")
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
