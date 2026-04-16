package com.augt.localseek.retrieval

import com.augt.localseek.model.SearchResult

data class HybridResult(
    val result: SearchResult,
    val bm25Score: Float,
    val denseScore: Float
)
