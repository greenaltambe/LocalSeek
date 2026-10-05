package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult

data class HybridResult(
    val doc: SearchResult, // Renamed 'result' to 'doc' to match the new code requirement
    val bm25Score: Float,
    val denseScore: Float
)
