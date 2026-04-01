package com.augt.localseek.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.BM25Retriever
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val bM25Retriever = BM25Retriever(application)
    private var searchJob: Job? = null;

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }

        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
                    results = emptyList(),
                    statusMessage = "Type to search",
                    isLoading = false,
                    latencyMs = 0L
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true, statusMessage = "Searching"
                )
            }
            delay(200)

            val startTime = System.currentTimeMillis()
            val list = bM25Retriever.search(newQuery)
            val latency = System.currentTimeMillis() - startTime

            // Push final results to the UI
            _uiState.update {
                it.copy(
                    results = list,
                    statusMessage = "${list.size} results",
                    isLoading = false,
                    latencyMs = latency
                )
            }
        }
    }
}