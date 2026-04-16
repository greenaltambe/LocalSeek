package com.augt.localseek.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ranking.LambdaMARTReranker
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.HybridRetriever
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

    // Initialize our ML Encoder
    private val denseEncoder = DenseEncoder(application)

    // Initialize our Retrievers
    private val bm25Retriever = BM25Retriever(application)
    private val denseRetriever = DenseRetriever(application, denseEncoder)
    private val hybridRetriever = HybridRetriever(bm25Retriever, denseRetriever)
    private val reranker = LambdaMARTReranker(application)

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

            val startTime = System.currentTimeMillis()

            // --- FINAL RE-RANKING PIPELINE ---
            // 1. Get hybrid candidates (with original scores)
            val candidates = hybridRetriever.search(newQuery)
            // 2. Re-rank them with the AI model
            val list = reranker.rerank(candidates)
            
            val latency = System.currentTimeMillis() - startTime

            _uiState.update {
                it.copy(
                    results = list,
                    statusMessage = if (list.isEmpty()) "No results" else "Final Rank: ${list.size} results",
                    isLoading = false,
                    latencyMs = latency
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Prevent memory leaks by shutting down the TensorFlow Lite model when the app closes
        denseEncoder.close()
    }
}
