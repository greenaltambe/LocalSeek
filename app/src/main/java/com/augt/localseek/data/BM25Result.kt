package com.augt.localseek.data

data class BM25Result(
    val id: Long,
    val filePath: String,
    val title: String,
    val body: String,
    val fileType: String,
    val modifiedAt: Long,
    val score: Float         // This will hold the raw bm25() score from SQLite
)
