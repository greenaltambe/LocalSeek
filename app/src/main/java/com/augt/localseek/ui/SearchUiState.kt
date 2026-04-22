package com.augt.localseek.ui

import com.augt.localseek.retrieval.FileResult

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
    val activeFilters: List<FilterType> = listOf(FilterType.All),
    val ragMode: Boolean = false,
    val ragAvailable: Boolean = false,
    val ragAnswer: String? = null,
    val ragError: String? = null,
    val ragCitations: List<String> = emptyList(),
    val llmLatencyMs: Long = 0L
)