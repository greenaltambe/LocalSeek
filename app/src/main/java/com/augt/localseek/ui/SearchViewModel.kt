package com.augt.localseek.ui

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.logging.PerformanceLogger
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val ENABLE_DENSE = false
        private const val BM25_WEIGHT = 0.6f
        private const val DENSE_WEIGHT = 0.4f
        private const val TAG_VALIDATION = "SearchValidation"
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val performanceLogger = PerformanceLogger()
    private val bm25Retriever = BM25Retriever(application)
    private val denseRetriever: DenseRetriever? = if (ENABLE_DENSE) DenseRetriever(application) else null

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(results = emptyList(), statusMessage = "Type to search", isLoading = false, latencyMs = 0L)
            }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Searching…") }
            delay(200)

            val query = normalizeQuery(newQuery)
            val analyzedTokens = analyzeTokens(query)
            Log.d(TAG_VALIDATION, "[VALIDATION] Query analyzed tokens: $analyzedTokens")

            val memBeforeMb = performanceLogger.memoryUsageMb()
            val totalStartMs = System.currentTimeMillis()

            var bm25Results = emptyList<SearchResult>()
            val bm25LatencyMs = measureTimeMillis {
                bm25Results = bm25Retriever.search(query)
            }

            var denseResults = emptyList<SearchResult>()
            val denseLatencyMs = if (ENABLE_DENSE) {
                measureTimeMillis {
                    denseResults = denseRetriever?.search(query).orEmpty()
                }
            } else {
                0L
            }

            var finalResults = emptyList<SearchResult>()
            val fusionLatencyMs = measureTimeMillis {
                finalResults = if (ENABLE_DENSE) {
                    weightedFusion(bm25Results, denseResults)
                } else {
                    bm25Results
                }
            }

            val totalLatencyMs = System.currentTimeMillis() - totalStartMs
            val memAfterMb = performanceLogger.memoryUsageMb()

            performanceLogger.logQuery(
                query = query,
                bm25LatencyMs = bm25LatencyMs,
                denseLatencyMs = denseLatencyMs,
                fusionLatencyMs = fusionLatencyMs,
                totalLatencyMs = totalLatencyMs,
                bm25Count = bm25Results.size,
                denseCount = denseResults.size,
                finalCount = finalResults.size,
                memoryBeforeMb = memBeforeMb,
                memoryAfterMb = memAfterMb
            )

            logTopResults(query, finalResults)

            _uiState.update {
                it.copy(
                    results = finalResults,
                    statusMessage = if (finalResults.isEmpty()) {
                        "No results"
                    } else {
                        "BM25: ${finalResults.size} results"
                    },
                    isLoading = false,
                    latencyMs = totalLatencyMs
                )
            }
        }
    }

    fun onToggleShowScores() {
        _uiState.update { current -> current.copy(showScores = !current.showScores) }
    }

    @VisibleForTesting
    internal fun analyzeTokens(query: String): List<String> {
        return query
            .lowercase()
            .split("\\s+".toRegex())
            .map { it.trim().replace("[^a-z0-9_]".toRegex(), "") }
            .filter { it.isNotBlank() }
    }

    private fun normalizeQuery(query: String): String = query.trim().lowercase()

    private fun weightedFusion(
        bm25Results: List<SearchResult>,
        denseResults: List<SearchResult>
    ): List<SearchResult> {
        val byId = mutableMapOf<Long, SearchResult>()
        val weightedScores = mutableMapOf<Long, Float>()

        bm25Results.forEach { result ->
            byId[result.id] = result
            weightedScores[result.id] = (weightedScores[result.id] ?: 0f) + (BM25_WEIGHT * result.score)
        }

        denseResults.forEach { result ->
            byId[result.id] = result
            weightedScores[result.id] = (weightedScores[result.id] ?: 0f) + (DENSE_WEIGHT * result.score)
        }

        return weightedScores.entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, score) -> byId[id]?.copy(score = score) }
    }

    private fun logTopResults(query: String, results: List<SearchResult>) {
        results.take(5).forEachIndexed { index, result ->
            Log.d(
                TAG_VALIDATION,
                "[VALIDATION] Query: \"$query\" | #${index + 1}: ${result.title} | score=${"%.4f".format(result.score)}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        denseRetriever?.close()
    }
}
