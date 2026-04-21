package com.augt.localseek.ui.settings

data class AppSettings(
    val enableDenseRetrieval: Boolean = true,
    val enableReranking: Boolean = true,
    val enableQueryExpansion: Boolean = true,
    val maxResults: Int = 20,
    val batteryAwareMode: Boolean = true,
    val adaptiveLsh: Boolean = true,
    val memoryMode: MemoryMode = MemoryMode.AUTO,
    val chunkSize: Int = 150,
    val chunkOverlap: Int = 40,
    val autoReindex: Boolean = false,
    val showDebugInfo: Boolean = false,
    val verboseLogging: Boolean = false
)

enum class MemoryMode {
    IN_MEMORY,
    STREAMING,
    AUTO
}

data class IndexStats(
    val totalFiles: Int = 0,
    val totalChunks: Int = 0,
    val indexSizeBytes: Long = 0,
    val lastUpdated: Long = 0,
    val isHealthy: Boolean = false
)

