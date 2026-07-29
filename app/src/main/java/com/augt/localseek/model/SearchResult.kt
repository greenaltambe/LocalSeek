package com.augt.localseek.model

enum class EntityType {
    FILE, APP, CONTACT
}

data class SearchResult(
    val id: Long,
    val title: String,
    val snippet: String,      // short preview of matching content
    val filePath: String,     // For FILE it's path, for APP it's package, for CONTACT it's ID
    val fileType: String,     // "txt", "pdf", "md", "app", "contact"
    val score: Float,         // relevance score, 0.0–1.0
    val modifiedAt: Long,     // epoch milliseconds (File.lastModified())
    val embedding: FloatArray? = null,
    val sizeBytes: Long = 0L,
    val entityType: EntityType = EntityType.FILE
)
