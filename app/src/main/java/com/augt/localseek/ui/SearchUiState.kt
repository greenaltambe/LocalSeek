package com.augt.localseek.ui

import com.augt.localseek.model.SearchResult

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val statusMessage: String = "Type to search",
    val isLoading: Boolean = false,
    val latencyMs: Long = 0L,
    val showScores: Boolean = false
)