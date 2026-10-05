package com.augt.localseek.ui

import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.ToolsPanelState
import com.augt.localseek.tools.UiPrefs
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.tools.WebEngines

data class SearchUiState(
    val query: String = "",
    val results: List<FileResult> = emptyList(),
    val statusMessage: String = "Type to search",
    val isLoading: Boolean = false,
    val loadingStage: String = "Searching...",
    val loadingProgress: Float = 0f,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val showScores: Boolean = false,
    val fusionMode: FusionMode = FusionMode.GLOBAL_NORMALIZATION,
    val activeFilters: List<FilterType> = listOf(FilterType.All),
    val benchmarkMode: Boolean = false,

    // Prefix chip ("g", "c", ...): when set, [query] holds only the text after the prefix.
    val prefix: Alias? = null,
    // Client-side type chip + Filters sheet choices, applied to the retrieved list
    val filters: ResultFilters = ResultFilters(),
    // Local-only recent queries (empty when the user turned the feature off)
    val recents: List<String> = emptyList(),
    val uiPrefs: UiPrefs = UiPrefs(),
    val engines: List<WebEngine> = WebEngines.DEFAULTS,

    // Tools layer (calculator, converters, aliases...) shown above the results
    val tools: ToolsPanelState = ToolsPanelState(),

    // Pinned apps/contacts/files (resolved from DataStore pins) and the ids of all pins
    val pinned: List<FileResult> = emptyList(),
    val pinnedIds: Set<String> = emptySet(),

    // Qrels / Evaluation Mode
    val isEvaluationMode: Boolean = false,
    val evaluationPool: List<com.augt.localseek.retrieval.PooledCandidate> = emptyList(),
    val currentEvaluationIndex: Int = 0,
    val evaluationQuery: String = "",

    // Background Indexing Status
    val isIndexing: Boolean = false,
    val indexingStatus: String? = null,
    val indexBanner: IndexBannerState = IndexBannerState.Hidden
) {
    /**
     * Web-search bar: show it only when results are empty AND the query is non-blank.
     */
    val showWebSearchBar: Boolean
        get() = results.isEmpty() && query.isNotBlank()

    /** The pinned web row: visible whenever there is a query and engines to offer, whatever the results are. */
    val showWebRow: Boolean
        get() = query.isNotBlank() && tools.webActions.isNotEmpty()

    /** Everything typed in the bar, prefix included. */
    val rawText: String
        get() = if (prefix != null) prefix.trigger + " " + query else query

    val showWebSearch: Boolean
        get() = showWebSearchBar
}
