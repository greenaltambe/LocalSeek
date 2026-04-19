package com.augt.localseek.ui

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.logging.measureSuspendTime
import com.augt.localseek.logging.PerformanceLogger
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionRanker
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.ResultAggregator
import com.augt.localseek.model.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val queryCache = QueryCache(maxSize = 50)
    private var latestAggregatedResults: List<FileResult> = emptyList()

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

            val cachedResults = queryCache.get(query)
            if (cachedResults != null) {
                latestAggregatedResults = cachedResults
                _uiState.update {
                    it.copy(
                        results = applyFilters(cachedResults, it.activeFilters),
                        statusMessage = "Cache: ${cachedResults.size} files",
                        isLoading = false,
                        latencyMs = 2L
                    )
                }
                return@launch
            }

            val memBeforeMb = performanceLogger.memoryUsageMb()
            val totalStartMs = System.currentTimeMillis()

            var bm25LatencyMs = 0L
            var denseLatencyMs = 0L
            val (bm25Results, denseResults) = coroutineScope {
                val bm25Deferred = async {
                    val (result, duration) = measureSuspendTime("BM25") { bm25Retriever.search(query, 100) }
                    result to duration
                }
                val denseDeferred = async {
                    val (result, duration) = measureSuspendTime("Dense") {
                        denseRetriever?.search(query, 50).orEmpty()
                    }
                    result to duration
                }

                val (bm25, bm25Duration) = bm25Deferred.await()
                bm25LatencyMs = bm25Duration

                var denseDuration = 0L
                val dense = if (ENABLE_DENSE && denseRetriever != null) {
                    if (denseRetriever.shouldSkipDense(bm25)) {
                        denseDeferred.cancel()
                        emptyList()
                    } else {
                        val (denseResult, duration) = denseDeferred.await()
                        denseDuration = duration
                        denseResult
                    }
                } else {
                    denseDeferred.cancel()
                    emptyList()
                }

                denseLatencyMs = denseDuration
                bm25 to dense
            }

            val (finalResults, fusionLatencyMs) = measureSuspendTime("Fusion") {
                rankAndDiversify(query, bm25Results, denseResults)
            }

            latestAggregatedResults = ResultAggregator.aggregateToFiles(finalResults, query)
            queryCache.put(query, latestAggregatedResults)
            val filteredResults = applyFilters(latestAggregatedResults, _uiState.value.activeFilters)

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
                finalCount = filteredResults.size,
                memoryBeforeMb = memBeforeMb,
                memoryAfterMb = memAfterMb
            )

            logTopResults(query, filteredResults)

            _uiState.update {
                it.copy(
                    results = filteredResults,
                    statusMessage = if (filteredResults.isEmpty()) {
                        "No results"
                    } else {
                        if (ENABLE_DENSE) "Hybrid: ${filteredResults.size} files" else "BM25: ${filteredResults.size} files"
                    },
                    isLoading = false,
                    latencyMs = totalLatencyMs
                )
            }
        }
    }

    fun onFileTypeFilterChanged(type: String?) {
        val filters = if (type.isNullOrBlank()) {
            listOf(FilterType.All)
        } else {
            listOf(FilterType.FileType(type.lowercase()))
        }
        applyCurrentFilters(filters)
    }

    fun onDateRangeFilterChanged(start: Long, end: Long) {
        val filters = listOf(FilterType.DateRange(start, end))
        applyCurrentFilters(filters)
    }

    private fun applyCurrentFilters(filters: List<FilterType>) {
        val filtered = applyFilters(latestAggregatedResults, filters)
        _uiState.update {
            it.copy(
                activeFilters = filters,
                results = filtered,
                statusMessage = if (filtered.isEmpty()) "No results" else "${filtered.size} files"
            )
        }
    }

    fun applyFilters(results: List<FileResult>, filters: List<FilterType>): List<FileResult> {
        var filtered = results

        filters.forEach { filter ->
            filtered = when (filter) {
                is FilterType.FileType -> filtered.filter { it.fileType.equals(filter.type, ignoreCase = true) }
                is FilterType.DateRange -> filtered.filter { it.modifiedAt in filter.start..filter.end }
                FilterType.All -> filtered
            }
        }

        return filtered
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
                    sizeBytes = it.sizeBytes,
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
                embedding = it.embedding,
                sizeBytes = it.sizeBytes
            )
        }
    }

    private fun logTopResults(query: String, results: List<FileResult>) {
        results.take(5).forEachIndexed { index, result ->
            Log.d(
                TAG_VALIDATION,
                "[VALIDATION] Query: \"$query\" | #${index + 1}: ${result.title} | score=${"%.4f".format(result.bestScore)}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        denseRetriever?.close()
    }
}
