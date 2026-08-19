package com.augt.localseek.ui

import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.FusionMode

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
    val ragMode: Boolean = false,
    val ragAvailable: Boolean = false,
    val ragAvailabilityHint: String? = null,
    val ragAnswer: String? = null,
    val ragError: String? = null,
    val ragCitations: List<String> = emptyList(),
    val llmLatencyMs: Long = 0L,
    val aiAnswerExpanded: Boolean = true,
    val benchmarkMode: Boolean = false,
    
    // Qrels / Evaluation Mode
    val isEvaluationMode: Boolean = false,
    val evaluationPool: List<com.augt.localseek.retrieval.PooledCandidate> = emptyList(),
    val currentEvaluationIndex: Int = 0,
    val evaluationQuery: String = ""
)
