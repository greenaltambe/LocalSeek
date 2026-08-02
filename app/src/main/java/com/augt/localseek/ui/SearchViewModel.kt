package com.augt.localseek.ui

import android.app.Application
import android.content.Intent
import android.content.ActivityNotFoundException
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.logging.PerformanceLogger
import com.augt.localseek.logging.measureSuspendTime
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.llm.Phi3LLM
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.CrossEncoderReranker
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.retrieval.FusionRanker
import com.augt.localseek.retrieval.ResultAggregator
import com.augt.localseek.search.query.QueryExpander
import com.augt.localseek.search.query.QueryProcessor
import com.augt.localseek.ui.settings.SettingsRepository
import androidx.core.content.FileProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val ENABLE_DENSE = true
        private const val TAG_VALIDATION = "SearchValidation"
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val ragEngine
        get() = (getApplication<Application>() as LocalSeekApplication).ragEngine

    private val performanceLogger = PerformanceLogger()
    private val bm25Retriever = BM25Retriever(application)
    private val denseRetriever: DenseRetriever? = if (ENABLE_DENSE) DenseRetriever(application) else null
    private val queryProcessorEncoder = DenseEncoder(application)
    private val queryProcessor = QueryProcessor(application, queryProcessorEncoder)
    private val settingsRepository = SettingsRepository(application)
    private val fusionRanker = FusionRanker()
    private val crossEncoderReranker = CrossEncoderReranker(application)
    private val queryCache = QueryCache(maxSize = 50)
    private var latestAggregatedResults: List<FileResult> = emptyList()

    private val runSessionId = java.util.UUID.randomUUID().toString()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            refreshRagAvailability()
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(
                    showScores = settings.showDebugInfo,
                    fusionMode = if (settings.enablePerTypeNormalization) FusionMode.PER_TYPE_NORMALIZATION else FusionMode.GLOBAL_NORMALIZATION,
                    benchmarkMode = settings.enableBenchmarkMode
                ) }
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
                    results = emptyList(),
                    statusMessage = "Type to search",
                    isLoading = false,
                    loadingStage = "Idle",
                    loadingProgress = 0f,
                    errorMessage = null,
                    latencyMs = 0L,
                    ragAnswer = null,
                    ragError = null,
                    ragCitations = emptyList(),
                    llmLatencyMs = 0L
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(200)
            executeSearch(newQuery, includeRag = false)
        }
    }

    private suspend fun executeSearch(rawQuery: String, includeRag: Boolean) {
        Log.d("RAG_DEBUG", "includeRag=$includeRag, ragMode=${_uiState.value.ragMode}")
        Log.d("RAG_DEBUG", "ragAvailable=${ragEngine.isAvailable()}")

        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = "Processing query",
                loadingProgress = 0.15f,
                errorMessage = null,
                statusMessage = "Searching...",
                ragAnswer = null,
                ragError = null,
                ragCitations = emptyList(),
                llmLatencyMs = 0L
            )
        }

        val processed = queryProcessor.process(rawQuery)
        _uiState.update { it.copy(loadingStage = "Retrieving results", loadingProgress = 0.45f) }

        val query = normalizeQuery(processed.normalized.normalized)
        val analyzedTokens = processed.keyTerms.ifEmpty { analyzeTokens(query) }
        Log.d(TAG_VALIDATION, "[VALIDATION] Query analyzed tokens: $analyzedTokens")

        val queryFilters = buildFiltersFromProcessed(processed.filters)
        if (queryFilters.isNotEmpty()) {
            _uiState.update { it.copy(activeFilters = queryFilters) }
        }

        val cachedResults = queryCache.get(query)
        if (cachedResults != null) {
            latestAggregatedResults = cachedResults
            _uiState.update {
                it.copy(
                    results = applyFilters(cachedResults, it.activeFilters),
                    statusMessage = "Cache: ${cachedResults.size} results",
                    isLoading = false,
                    loadingStage = "Done",
                    loadingProgress = 1f,
                    errorMessage = null,
                    latencyMs = 2L
                )
            }
            return
        }

        val memBeforeMb = performanceLogger.memoryUsageMb()
        val totalStartMs = System.currentTimeMillis()

        var bm25LatencyMs = 0L
        var denseLatencyMs = 0L
        val (bm25Results, denseResults) = coroutineScope {
            val bm25Deferred = async {
                val (result, duration) = measureSuspendTime("BM25") { bm25Retriever.search(processed.bm25Query, 100) }
                result to duration
            }
            val denseDeferred = async {
                val (result, duration) = measureSuspendTime("Dense") {
                    denseRetriever?.search(processed.denseQuery, 50).orEmpty()
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

        _uiState.update { it.copy(loadingStage = "Reranking", loadingProgress = 0.8f) }

        val (rerankedResults, rerankLatencyMs) = measureSuspendTime("Rerank") {
            crossEncoderReranker.rerank(query, finalResults)
        }

        latestAggregatedResults = ResultAggregator.aggregateToFiles(rerankedResults, query)
        queryCache.put(query, latestAggregatedResults)
        val filteredResults = applyFilters(latestAggregatedResults, _uiState.value.activeFilters)

        val totalLatencyMs = System.currentTimeMillis() - totalStartMs
        val memAfterMb = performanceLogger.memoryUsageMb()

        performanceLogger.logQuery(
            query = query,
            bm25LatencyMs = bm25LatencyMs,
            denseLatencyMs = denseLatencyMs,
            fusionLatencyMs = fusionLatencyMs + rerankLatencyMs,
            totalLatencyMs = totalLatencyMs,
            bm25Count = bm25Results.size,
            denseCount = denseResults.size,
            finalCount = filteredResults.size,
            memoryBeforeMb = memBeforeMb,
            memoryAfterMb = memAfterMb
        )

        logTopResults(query, filteredResults)

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            runBenchmarkSuite(
                query = query,
                bm25Results = bm25Results,
                denseResults = denseResults,
                bm25LatencyMs = bm25LatencyMs,
                denseLatencyMs = denseLatencyMs,
                fusionLatencyMs = fusionLatencyMs,
                rerankLatencyMs = rerankLatencyMs,
                totalLatencyMs = totalLatencyMs
            )
        }

        var ragAnswer: String? = null
        var ragError: String? = null
        var ragCitations: List<String> = emptyList()
        var llmLatencyMs = 0L

        Log.d("RAG_DEBUG", "includeRag=$includeRag, ragMode=${_uiState.value.ragMode}")
        Log.d("RAG_DEBUG", "ragAvailable=${ragEngine.isAvailable()}")

        val ragModeSnapshot = _uiState.value.ragMode

        if (includeRag && ragModeSnapshot) {
            if (!ragEngine.isAvailable()) {
                refreshRagAvailability(forceInit = true)
            }
            if (ragEngine.isAvailable()) {
                _uiState.update { it.copy(loadingStage = "Generating AI answer", loadingProgress = 0.92f) }
                Log.d("RAG_DEBUG", "Entering RAG generation block")

                val ragResult = ragEngine.generateAnswer(rawQuery, filteredResults)

                Log.d("RAG_DEBUG", "RAG raw result: $ragResult")
                Log.d("RAG_DEBUG", "RAG answer length: ${ragResult.answer?.length}")
                Log.d("RAG_DEBUG", "RAG error: ${ragResult.error}")
                ragAnswer = ragResult.answer
                ragError = ragResult.error
                ragCitations = ragResult.citations
                llmLatencyMs = ragResult.llmLatencyMs
            } else {
                ragError = "AI answers are not available on this device"
            }
        }

        Log.d("RAG_DEBUG", "ragAnswer=$ragAnswer, ragError=$ragError")

        _uiState.update {
            it.copy(
                results = filteredResults,
                statusMessage = if (filteredResults.isEmpty()) {
                    "No results"
                } else {
                    "Found ${filteredResults.size} results"
                },
                isLoading = false,
                loadingStage = "Done",
                loadingProgress = 1f,
                errorMessage = null,
                latencyMs = totalLatencyMs,
                ragAnswer = ragAnswer,
                ragError = ragError,
                ragCitations = ragCitations,
                llmLatencyMs = llmLatencyMs
            )
        }
    }

    private suspend fun refreshRagAvailability(forceInit: Boolean = false) {
        if (forceInit || !ragEngine.isAvailable()) {
            ragEngine.initialize()
        }
        val available = ragEngine.isAvailable()
        val hint = if (available) null else resolveRagAvailabilityHint()
        _uiState.update {
            it.copy(
                ragAvailable = available,
                ragAvailabilityHint = hint
            )
        }
    }

    private suspend fun resolveRagAvailabilityHint(): String? {
        val settings = settingsRepository.settings.first()
        val hasGeminiKey = settings.geminiApiKey.isNotBlank()
        val phi3Available = Phi3LLM.isAvailable(getApplication())

        return when {
            !hasGeminiKey && !phi3Available -> "AI answers require Gemini API key or Phi-3 model. Configure in settings."
            hasGeminiKey && !phi3Available -> "AI answers unavailable: Gemini quota exceeded. Download Phi-3 as backup or retry later."
            else -> null
        }
    }

    // Phase 9 UI compatibility helpers.
    fun updateQuery(newQuery: String) = onQueryChanged(newQuery)

    fun search() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            executeSearch(query, includeRag = true)
        }
    }

    fun toggleRagMode() {
        val available = ragEngine.isAvailable()
        _uiState.update {
            it.copy(
                ragAvailable = available,
                ragAvailabilityHint = if (available) null else _uiState.value.ragAvailabilityHint,
                ragMode = if (available) !it.ragMode else false,
                ragError = if (!available) "AI answers are not available on this device" else null
            )
        }
    }

    fun toggleAiAnswerExpanded() {
        _uiState.update { it.copy(aiAnswerExpanded = !it.aiAnswerExpanded) }
    }

    fun removeFilter(filterType: FilterType) {
        when (filterType) {
            is FilterType.FileType -> onFileTypeFilterChanged(null)
            is FilterType.DateRange -> applyCurrentFilters(listOf(FilterType.All))
            FilterType.All -> applyCurrentFilters(listOf(FilterType.All))
        }
    }

    fun onResultClick(result: FileResult) {
        when (result.entityType) {
            EntityType.FILE -> openFile(result)
            EntityType.APP -> launchApp(result.filePath)
            EntityType.CONTACT -> openContact(result.filePath)
        }
    }

    private fun openFile(result: FileResult) {
        viewModelScope.launch {
            try {
                val file = File(result.filePath)
                if (!file.exists()) {
                    _uiState.update { it.copy(errorMessage = "File not found: ${file.name}") }
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )

                val mimeType = when (result.fileType.lowercase()) {
                    "pdf" -> "application/pdf"
                    "txt" -> "text/plain"
                    "md", "markdown" -> "text/markdown"
                    "json" -> "application/json"
                    "html" -> "text/html"
                    else -> "text/plain"
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    getApplication<Application>().startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    _uiState.update {
                        it.copy(errorMessage = "No app found to open ${result.fileType} files")
                    }
                }
            } catch (e: Exception) {
                Log.e("SearchViewModel", "Error opening file", e)
                _uiState.update { it.copy(errorMessage = "Failed to open file: ${e.message}") }
            }
        }
    }

    private fun launchApp(packageName: String) {
        val pm = getApplication<Application>().packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } else {
            _uiState.update { it.copy(errorMessage = "Cannot launch app: $packageName") }
        }
    }

    private fun openContact(contactId: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_URI, contactId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Cannot open contact") }
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
                statusMessage = if (filtered.isEmpty()) "No results" else "${filtered.size} results"
            )
        }
    }

    private fun buildFiltersFromProcessed(processedFilters: QueryExpander.QueryFilters): List<FilterType> {
        val filters = mutableListOf<FilterType>()

        processedFilters.fileType?.let { filters.add(FilterType.FileType(it)) }
        processedFilters.dateAfter?.let { start ->
            val end = processedFilters.dateBefore ?: Long.MAX_VALUE
            filters.add(FilterType.DateRange(start, end))
        }

        if (filters.isEmpty()) filters.add(FilterType.All)
        return filters
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

    private suspend fun runBenchmarkSuite(
        query: String,
        bm25Results: List<SearchResult>,
        denseResults: List<SearchResult>,
        bm25LatencyMs: Long,
        denseLatencyMs: Long,
        fusionLatencyMs: Long,
        rerankLatencyMs: Long,
        totalLatencyMs: Long
    ) {
        val db = AppDatabase.getInstance(getApplication())
        val corpusSizeChunks = db.chunkDao().countAllChunks()
        val corpusSizeApps = db.appDao().getCount()
        val corpusSizeContacts = db.contactDao().getCount()

        val bm25Map = bm25Results.associateBy { it.entityType to it.id }
        val denseMap = denseResults.associateBy { it.entityType to it.id }
        val allKeys = (bm25Map.keys + denseMap.keys).distinct()

        val candidates = allKeys.mapNotNull { key ->
            val bm25 = bm25Map[key]
            val dense = denseMap[key]
            val source = dense ?: bm25
            source?.let {
                FusionCandidate(
                    id = it.id,
                    title = it.title,
                    snippet = it.snippet,
                    filePath = it.filePath,
                    fileType = it.fileType,
                    modifiedAt = it.modifiedAt,
                    sizeBytes = it.sizeBytes,
                    bm25Score = bm25?.score?.toDouble(),
                    denseScore = dense?.score?.toDouble(),
                    embedding = dense?.embedding,
                    entityType = it.entityType
                )
            }
        }

        val queryId = query.trim().lowercase().hashCode().toString()
        val timestamp = System.currentTimeMillis()
        val deviceModel = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE

        fun getMem(): Float {
            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            return mi.totalPss / 1024f
        }

        fun getBat(): Int {
            val bm = getApplication<Application>().getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        val memBefore = getMem()
        val batBefore = if (_uiState.value.benchmarkMode) getBat() else null

        // We'll capture peak as we go
        var peakMem = memBefore

        fun logMode(modeName: String, rankedResults: List<FusionCandidate>, batAfter: Int? = null) {
            val currentMem = getMem()
            if (currentMem > peakMem) peakMem = currentMem

            viewModelScope.launch {
                val record = BenchmarkRunEntity(
                    runSessionId = runSessionId,
                    queryId = queryId,
                    queryText = query,
                    timestamp = timestamp,
                    deviceModel = deviceModel,
                    androidVersion = androidVersion,
                    backend = modeName,
                    corpusSizeChunks = corpusSizeChunks,
                    corpusSizeApps = corpusSizeApps,
                    corpusSizeContacts = corpusSizeContacts,
                    latencyBm25Ms = bm25LatencyMs,
                    latencyDenseMs = denseLatencyMs,
                    latencyFusionMs = fusionLatencyMs,
                    latencyRerankMs = if (modeName.startsWith("hybrid")) rerankLatencyMs else 0L,
                    latencyTotalMs = totalLatencyMs,
                    memoryMbPeak = peakMem,
                    batteryPctBefore = batBefore,
                    batteryPctAfter = batAfter,
                    resultIdsJson = org.json.JSONArray(rankedResults.take(20).map { "${it.entityType}:${it.id}" }).toString(),
                    resultScoresJson = org.json.JSONArray(rankedResults.take(20).map { it.finalScore }).toString(),
                    resultEntityTypesJson = org.json.JSONArray(rankedResults.take(20).map { it.entityType.name }).toString()
                )
                BenchmarkLogger.logRun(getApplication(), record)
            }
        }

        if (_uiState.value.benchmarkMode) {
            // BM25-only
            val bm25Only = candidates.filter { it.bm25Score != null }
                .sortedByDescending { it.bm25Score ?: 0.0 }
            logMode("bm25", bm25Only)

            // Dense-only
            val denseOnly = candidates.filter { it.denseScore != null }
                .sortedByDescending { it.denseScore ?: 0.0 }
            logMode("dense_lsh", denseOnly)

            // Fusion modes
            logMode("hybrid_global", fusionRanker.rank(query, candidates, FusionMode.GLOBAL_NORMALIZATION))
            logMode("hybrid_per_type", fusionRanker.rank(query, candidates, FusionMode.PER_TYPE_NORMALIZATION))
            logMode("hybrid_threshold", fusionRanker.rank(query, candidates, FusionMode.PER_TYPE_WITH_THRESHOLD), getBat())
        } else {
            // Log only active mode
            val currentMode = _uiState.value.fusionMode
            val backendName = when (currentMode) {
                FusionMode.GLOBAL_NORMALIZATION -> "hybrid_global"
                FusionMode.PER_TYPE_NORMALIZATION -> "hybrid_per_type"
                FusionMode.PER_TYPE_WITH_THRESHOLD -> "hybrid_threshold"
            }
            logMode(backendName, fusionRanker.rank(query, candidates, currentMode))
        }
    }

    private fun rankAndDiversify(
        query: String,
        bm25Results: List<SearchResult>,
        denseResults: List<SearchResult>
    ): List<SearchResult> {
        val bm25Map = bm25Results.associateBy { it.entityType to it.id }
        val denseMap = denseResults.associateBy { it.entityType to it.id }
        val allKeys = (bm25Map.keys + denseMap.keys).distinct()

        val candidates = allKeys.mapNotNull { key ->
            val bm25 = bm25Map[key]
            val dense = denseMap[key]
            val source = dense ?: bm25
            source?.let {
                FusionCandidate(
                    id = it.id,
                    title = it.title,
                    snippet = it.snippet,
                    filePath = it.filePath,
                    fileType = it.fileType,
                    modifiedAt = it.modifiedAt,
                    sizeBytes = it.sizeBytes,
                    bm25Score = bm25?.score?.toDouble(),
                    denseScore = dense?.score?.toDouble(),
                    embedding = dense?.embedding,
                    entityType = it.entityType
                )
            }
        }

        // TASK 3: Comparison logging
        val globalRanked = fusionRanker.rank(query, candidates, FusionMode.GLOBAL_NORMALIZATION)
        val perTypeRanked = fusionRanker.rank(query, candidates, FusionMode.PER_TYPE_NORMALIZATION)
        val perTypeWithThresholdRanked = fusionRanker.rank(query, candidates, FusionMode.PER_TYPE_WITH_THRESHOLD)
        performanceLogger.logCalibrationComparison(query, globalRanked, perTypeRanked, perTypeWithThresholdRanked)

        val currentMode = _uiState.value.fusionMode
        val ranked = when (currentMode) {
            FusionMode.GLOBAL_NORMALIZATION -> globalRanked
            FusionMode.PER_TYPE_NORMALIZATION -> perTypeRanked
            FusionMode.PER_TYPE_WITH_THRESHOLD -> perTypeWithThresholdRanked
        }
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
                sizeBytes = it.sizeBytes,
                entityType = it.entityType
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
        crossEncoderReranker.close()
        queryProcessorEncoder.close()
    }
}
