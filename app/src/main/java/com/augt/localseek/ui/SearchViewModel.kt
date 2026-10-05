package com.augt.localseek.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.augt.localseek.BuildConfig
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.di.AppContainer
import com.augt.localseek.indexing.IndexWorker
import com.augt.localseek.logging.PerformanceLogger
import com.augt.localseek.notify.IndexProgress
import com.augt.localseek.notify.IndexProgressKeys
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.search.RetrieverKind
import com.augt.localseek.search.RetrieverOutcome
import com.augt.localseek.search.query.QueryExpander
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.Aliases
import com.augt.localseek.tools.Pin
import com.augt.localseek.tools.PinResolver
import com.augt.localseek.tools.Pins
import com.augt.localseek.tools.ToolsPanelBuilder
import com.augt.localseek.tools.ToolsPanelState
import com.augt.localseek.tools.ToolsRepository
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.tools.WebEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SearchViewModel @JvmOverloads constructor(
    application: Application,
    val container: AppContainer = (application as? LocalSeekApplication)?.appContainer ?: AppContainer(application)
) : AndroidViewModel(application) {

    companion object {
        private const val TAG_VALIDATION = "SearchValidation"
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val performanceLogger = PerformanceLogger()
    private val searchEngine = container.searchEngine
    private val benchmarkRunner = container.benchmarkRunner
    private val queryProcessor = container.queryProcessor
    private val settingsRepository = container.settingsRepository
    private var currentSettings = com.augt.localseek.ui.settings.AppSettings()
    private val queryCache = QueryCache(maxSize = 50)
    private var latestAggregatedResults: List<FileResult> = emptyList()

    private val runSessionId = java.util.UUID.randomUUID().toString()

    private var searchJob: Job? = null

    private val toolsRepository = ToolsRepository(application)
    private val toolsPanelBuilder = ToolsPanelBuilder()
    private var currentAliases: List<Alias> = Aliases.DEFAULTS
    private var currentEngines: List<WebEngine> = WebEngines.DEFAULTS

    private val pinResolver = PinResolver(container.database)

    init {
        viewModelScope.launch {
            toolsRepository.pins.collect { pins ->
                val resolved = try {
                    pinResolver.resolve(pins)
                } catch (e: Exception) {
                    Log.w("SearchViewModel", "Failed to resolve pins", e)
                    emptyList()
                }
                _uiState.update { it.copy(pinned = resolved, pinnedIds = pins.map { p -> p.id }.toSet()) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                _uiState.update { it.copy(
                    showScores = settings.showDebugInfo,
                    fusionMode = if (settings.enablePerTypeNormalization) FusionMode.PER_TYPE_NORMALIZATION else FusionMode.GLOBAL_NORMALIZATION,
                    benchmarkMode = settings.enableBenchmarkMode
                ) }
            }
        }
        viewModelScope.launch {
            toolsRepository.aliases.collect { list ->
                currentAliases = list
                refreshToolsPanel()
            }
        }
        viewModelScope.launch {
            toolsRepository.uiPrefs.collect { prefs -> _uiState.update { it.copy(uiPrefs = prefs) } }
        }
        viewModelScope.launch {
            toolsRepository.recentSearches.collect { list -> _uiState.update { it.copy(recents = list) } }
        }
        viewModelScope.launch {
            toolsRepository.engines.collect { list ->
                currentEngines = list
                _uiState.update { it.copy(engines = list) }
                refreshToolsPanel()
            }
        }
        viewModelScope.launch {
            try {
                WorkManager.getInstance(application)
                    .getWorkInfosByTagFlow(IndexWorker.TAG)
                    .collect { workInfos ->
                        val snapshots = workInfos.map {
                            IndexWorkSnapshot(
                                it.state, it.tags,
                                IndexProgress.fromData(
                                    it.progress.getInt(IndexProgressKeys.DONE, -1),
                                    it.progress.getInt(IndexProgressKeys.TOTAL, -1)
                                )
                            )
                        }
                        val status = IndexingStatusMapper.map(snapshots)
                        val banner = IndexBanner.reduce(_uiState.value.indexBanner, IndexBanner.observe(snapshots))
                        _uiState.update {
                            it.copy(isIndexing = status.isIndexing, indexingStatus = status.label, indexBanner = banner)
                        }
                        if (banner is IndexBannerState.UpToDate) scheduleBannerDismiss()
                    }
            } catch (e: Exception) {
                Log.w("SearchViewModel", "Failed to observe WorkManager status", e)
            }
        }
    }

    /**
     * Engine and calculator prefixes are resolved by the tools layer from the full "<prefix> <text>" input; scoped
     * prefixes only narrow the local search, so the tools layer sees just the text after them.
     */
    private fun toolsInput(prefix: Alias?, query: String): String =
        if (prefix != null && !prefix.isScope) prefix.trigger + " " + query else query

    private var bannerDismissJob: Job? = null

    /** "Index up to date" stays for 3 s, then the banner slides away. */
    private fun scheduleBannerDismiss() {
        bannerDismissJob?.cancel()
        bannerDismissJob = viewModelScope.launch {
            delay(IndexBanner.UP_TO_DATE_MS)
            _uiState.update { it.copy(indexBanner = IndexBanner.dismissUpToDate(it.indexBanner)) }
        }
    }

    /** "Resume" / "Run now": replaces a failed or delayed index request with an immediate one. Never touches a running one. */
    fun resumeIndexing() {
        if (_uiState.value.indexBanner is IndexBannerState.Running) return
        val request = androidx.work.OneTimeWorkRequestBuilder<IndexWorker>()
            .addTag(IndexWorker.TAG)
            .addTag(IndexWorker.TAG_ONE_TIME)
            .setInputData(androidx.work.workDataOf(IndexWorker.IN_FORCE_REINDEX to false))
            .build()
        WorkManager.getInstance(getApplication<Application>())
            .enqueueUniqueWork(IndexWorker.TAG_ONE_TIME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    private fun buildToolsPanel(prefix: Alias?, query: String): ToolsPanelState =
        toolsPanelBuilder.build(toolsInput(prefix, query), currentAliases, currentEngines)

    private fun refreshToolsPanel() {
        _uiState.update { it.copy(tools = buildToolsPanel(it.prefix, it.query)) }
    }

    /** True while the chip was just removed with backspace, so the typed trigger is not turned into a chip again. */
    private var prefixSuppressed = false

    /** Entry point for everything typed in the search bar: detects "<prefix><space>" and turns it into a chip. */
    fun onInput(text: String) {
        val state = _uiState.value
        if (state.prefix == null) {
            val match = if (prefixSuppressed) null else Aliases.matchPrefixChip(text, currentAliases)
            if (match != null && isUsablePrefix(match.alias)) {
                applyPrefix(match.alias, match.argument)
                return
            }
            if (prefixSuppressed && Aliases.matchPrefixChip(text, currentAliases) == null) prefixSuppressed = false
        }
        onQueryChanged(text)
    }

    private fun isUsablePrefix(alias: Alias): Boolean =
        alias.isScope || alias.target == Alias.CALCULATOR || currentEngines.any { it.id == alias.target }

    private fun applyPrefix(alias: Alias, rest: String) {
        val scope = when (alias.target) {
            Alias.SCOPE_APPS -> TypeScope.APPS
            Alias.SCOPE_CONTACTS -> TypeScope.CONTACTS
            Alias.SCOPE_FILES -> TypeScope.FILES
            Alias.SCOPE_IMAGES -> TypeScope.IMAGES
            Alias.SCOPE_SETTINGS -> TypeScope.DEVICE_SETTINGS
            else -> null
        }
        _uiState.update {
            it.copy(prefix = alias, filters = if (scope != null) it.filters.copy(scope = scope) else it.filters)
        }
        onQueryChanged(rest)
    }

    /** Removes the chip (backspace on an empty field or its close button); the trigger stays as plain text. */
    fun removePrefix() {
        val state = _uiState.value
        val prefix = state.prefix ?: return
        prefixSuppressed = true
        val restored = prefix.trigger + " " + state.query
        _uiState.update {
            it.copy(prefix = null, filters = if (prefix.isScope) it.filters.copy(scope = TypeScope.ALL) else it.filters)
        }
        onQueryChanged(restored)
        refreshResults()
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery, tools = buildToolsPanel(it.prefix, newQuery)) }
        searchJob?.cancel()

        val prefix = _uiState.value.prefix
        if (prefix != null && !prefix.isScope) {
            // Engine / calculator prefix: the tools layer answers, the local index is not searched.
            latestAggregatedResults = emptyList()
            _uiState.update {
                it.copy(results = emptyList(), isLoading = false, errorMessage = null, loadingProgress = 0f, latencyMs = 0L)
            }
            return
        }

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
                    results = emptyList(),
                    statusMessage = "Type to search",
                    isLoading = false,
                    loadingStage = "Idle",
                    loadingProgress = 0f,
                    errorMessage = null,
                    latencyMs = 0L
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(200)
            executeSearch(newQuery)
        }
    }

    /**
     * Runs one search. Any failure inside the engine is reported through [SearchUiState.errorMessage] instead of
     * crashing the app (an uncaught exception in viewModelScope would kill the process); cancellation from a newer
     * query is rethrown untouched.
     */
    private suspend fun executeSearch(rawQuery: String) {
        try {
            executeSearchInternal(rawQuery)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Search failed", e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingStage = "Error",
                    loadingProgress = 0f,
                    errorMessage = searchFailureMessage(e)
                )
            }
        }
    }

    private suspend fun executeSearchInternal(rawQuery: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingStage = "Processing query",
                loadingProgress = 0.15f,
                errorMessage = null,
                statusMessage = "Searching..."
            )
        }

        val processed = queryProcessor.process(rawQuery)
        val query = normalizeQuery(processed.normalized.normalized)
        val analyzedTokens = processed.keyTerms.ifEmpty { analyzeTokens(query) }
        if (BuildConfig.DEBUG) Log.d(TAG_VALIDATION, "[VALIDATION] Query analyzed tokens: $analyzedTokens")

        val queryFilters = buildFiltersFromProcessed(processed.filters)
        if (queryFilters.isNotEmpty()) {
            _uiState.update { it.copy(activeFilters = queryFilters) }
        }

        val currentConfig = SearchConfigFactory.build(
            settings = currentSettings,
            imageSearchAvailable = BuildConfig.ENABLE_IMAGE_SEARCH && currentSettings.enableImageSearch && ClipAssetPackManager.isModelAvailable(getApplication()),
            benchmarkMode = _uiState.value.benchmarkMode
        )

        val currentGeneration = container.indexGeneration.current

        // Bypass cache in benchmark mode
        val cachedResults = if (!currentConfig.benchmarkMode) {
            queryCache.get(query, currentConfig.configHash(), currentGeneration)
        } else null

        if (cachedResults != null) {
            latestAggregatedResults = cachedResults
            _uiState.update {
                it.copy(
                    results = displayResults(cachedResults, it.activeFilters, it.filters),
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

        _uiState.update { it.copy(loadingStage = "Retrieving results", loadingProgress = 0.45f) }

        val memBeforeMb = performanceLogger.memoryUsageMb()
        val outcome = searchEngine.search(rawQuery, currentConfig)
        val memAfterMb = performanceLogger.memoryUsageMb()

        latestAggregatedResults = outcome.results
        if (!currentConfig.benchmarkMode) {
            queryCache.put(query, outcome.results, currentConfig.configHash(), outcome.indexGeneration)
        }
        val filteredResults = _uiState.value.let { displayResults(latestAggregatedResults, it.activeFilters, it.filters) }

        val bm25Count = (outcome.candidatesByRetriever[RetrieverKind.BM25] as? RetrieverOutcome.Ran)?.candidates?.size ?: 0
        val denseCount = (outcome.candidatesByRetriever[RetrieverKind.DENSE] as? RetrieverOutcome.Ran)?.candidates?.size ?: 0

        performanceLogger.logQuery(
            query = outcome.query,
            bm25LatencyMs = outcome.bm25LatencyMs,
            denseLatencyMs = outcome.denseLatencyMs,
            fusionLatencyMs = outcome.fusionLatencyMs + outcome.rerankLatencyMs,
            totalLatencyMs = outcome.totalLatencyMs,
            bm25Count = bm25Count,
            denseCount = denseCount,
            finalCount = filteredResults.size,
            memoryBeforeMb = memBeforeMb,
            memoryAfterMb = memAfterMb
        )

        logTopResults(outcome.query, filteredResults)

        if (_uiState.value.benchmarkMode) {
            viewModelScope.launch(Dispatchers.Default) {
                benchmarkRunner.runBenchmarkSuite(
                    query = outcome.query,
                    runSessionId = runSessionId
                )
            }
        }

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
                latencyMs = outcome.totalLatencyMs
            )
        }
    }

    // Phase 9 UI compatibility helpers.
    fun updateQuery(newQuery: String) = onInput(newQuery)

    fun search() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        rememberQuery()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            executeSearch(query)
        }
    }

    fun removeFilter(filterType: FilterType) {
        when (filterType) {
            is FilterType.FileType -> onFileTypeFilterChanged(null)
            is FilterType.DateRange -> applyCurrentFilters(listOf(FilterType.All))
            FilterType.All -> applyCurrentFilters(listOf(FilterType.All))
        }
    }

    fun onTogglePin(result: FileResult) {
        val pin = Pin(result.entityType.name, result.stableKey)
        if (!Pins.isPinnable(pin.entityType, pin.stableKey)) return
        viewModelScope.launch { toolsRepository.togglePin(pin) }
    }

    fun onResultClick(result: FileResult) {
        rememberQuery()
        when (result.entityType) {
            EntityType.FILE -> openFile(result)
            EntityType.APP -> launchApp(result.filePath)
            EntityType.CONTACT -> openContact(result.filePath)
            EntityType.IMAGE -> openImage(result.filePath)
        }
    }

    private fun openImage(contentUriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUriString.toUri(), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.e("SearchViewModel", "Failed to open image URI", e)
            _uiState.update { it.copy(errorMessage = "Could not open image") }
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
        _uiState.update {
            val filtered = displayResults(latestAggregatedResults, filters, it.filters)
            it.copy(
                activeFilters = filters,
                results = filtered,
                statusMessage = if (filtered.isEmpty()) "No results" else "${filtered.size} results"
            )
        }
    }

    /** Query-derived filters first (from "pdf last week" style text), then the chip row and Filters sheet. */
    private fun displayResults(raw: List<FileResult>, queryFilters: List<FilterType>, filters: ResultFilters): List<FileResult> =
        ResultFilterEngine.apply(applyFilters(raw, queryFilters), filters)

    private fun refreshResults() {
        _uiState.update {
            val filtered = displayResults(latestAggregatedResults, it.activeFilters, it.filters)
            it.copy(results = filtered)
        }
    }

    /** Applies new chip-row / sheet filters to the results already retrieved; no new search is run. */
    fun onFiltersChanged(transform: ResultFilters.() -> ResultFilters) {
        _uiState.update { it.copy(filters = it.filters.transform()) }
        refreshResults()
    }

    fun clearFilters() = onFiltersChanged { cleared() }

    /** Launcher shortcut ("Search images") or text shared into the app; shared text is never logged or stored. */
    fun applyLaunchRequest(request: LaunchRequest) {
        when (request) {
            LaunchRequest.None -> Unit
            is LaunchRequest.Scope -> {
                onInput("")
                onFiltersChanged { copy(scope = request.scope) }
            }
            is LaunchRequest.Search -> {
                onFiltersChanged { copy(scope = TypeScope.ALL) }
                onInput(request.text)
            }
        }
    }

    // Recent searches: stored locally only (and only when the user has not turned them off).
    private fun rememberQuery() {
        val state = _uiState.value
        val text = state.rawText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch { toolsRepository.addRecentSearch(text) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { toolsRepository.clearRecentSearches() }
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

    private fun logTopResults(query: String, results: List<FileResult>) {
        // Result titles can be contact or file names: keep them out of release logcat.
        if (!BuildConfig.DEBUG) return
        results.take(5).forEachIndexed { index, result ->
            Log.d(
                TAG_VALIDATION,
                "[VALIDATION] Query: \"$query\" | #${index + 1}: ${result.title} | score=${"%.4f".format(result.bestScore)}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Model and retriever lifecycles are process-scoped and owned by AppContainer/ModelRegistry.
        // We do not close shared models on ViewModel destruction.
    }
}
