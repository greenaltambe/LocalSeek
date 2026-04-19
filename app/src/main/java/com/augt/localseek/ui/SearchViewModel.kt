package com.augt.localseek.ui

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.logging.PerformanceLogger
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionRanker
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
        const val ENABLE_DENSE = true
        private const val TAG_VALIDATION = "SearchValidation"
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val performanceLogger = PerformanceLogger()
    private val bm25Retriever = BM25Retriever(application)
    private val denseRetriever: DenseRetriever? = if (ENABLE_DENSE) DenseRetriever(application) else null
    private val fusionRanker = FusionRanker()

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
                finalResults = rankAndDiversify(query, bm25Results, denseResults)
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
                        if (ENABLE_DENSE) "Hybrid: ${finalResults.size} results" else "BM25: ${finalResults.size} results"
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

    private fun rankAndDiversify(
        query: String,
        bm25Results: List<SearchResult>,
        denseResults: List<SearchResult>
    ): List<SearchResult> {
        val bm25Map = bm25Results.associateBy { it.id }
        val denseMap = denseResults.associateBy { it.id }
        val allIds = (bm25Map.keys + denseMap.keys).distinct()

        val candidates = allIds.mapNotNull { id ->
            val bm25 = bm25Map[id]
            val dense = denseMap[id]
            val source = dense ?: bm25
            source?.let {
                FusionCandidate(
                    id = id,
                    title = it.title,
                    snippet = it.snippet,
                    filePath = it.filePath,
                    fileType = it.fileType,
                    modifiedAt = it.modifiedAt,
                    bm25Score = bm25?.score?.toDouble(),
                    denseScore = dense?.score?.toDouble(),
                    embedding = dense?.embedding
                )
            }
        }

        val ranked = fusionRanker.rank(query, candidates)
        val diversified = fusionRanker.diversify(ranked)

        return diversified.map {
            SearchResult(
                id = it.id,
                title = it.title,
                snippet = it.snippet,
                filePath = it.filePath,
                fileType = it.fileType,
                score = it.finalScore.toFloat(),
                modifiedAt = it.modifiedAt,
                embedding = it.embedding
            )
        }
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
