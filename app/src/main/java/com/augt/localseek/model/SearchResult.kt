package com.augt.localseek.model

data class SearchResult(
    val id: Long,
    val title: String,
    val snippet: String,      // short preview of matching content
    val filePath: String,
    val fileType: String,     // "txt", "pdf", "md", etc.
    val score: Float,         // relevance score, 0.0–1.0
    val modifiedAt: Long      // epoch milliseconds (File.lastModified())
)