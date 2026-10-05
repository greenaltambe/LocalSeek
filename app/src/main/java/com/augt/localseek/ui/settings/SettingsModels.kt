package com.augt.localseek.ui.settings

import com.augt.localseek.BuildConfig

data class AppSettings(
    val enableDenseRetrieval: Boolean = true,
    /** Off by default: measured cost is 6 s or more per query on-device and no quality gain is claimed. */
    val enableReranking: Boolean = false,
    val enableQueryExpansion: Boolean = true,
    val enableImageSearch: Boolean = BuildConfig.ENABLE_IMAGE_SEARCH,
    val maxResults: Int = 20,
    val batteryAwareMode: Boolean = true,
    val memoryMode: MemoryMode = MemoryMode.AUTO,
    val chunkSize: Int = 150,
    val chunkOverlap: Int = 40,
    val autoReindex: Boolean = false,
    val showDebugInfo: Boolean = false,
    val verboseLogging: Boolean = false,
    val enablePerTypeNormalization: Boolean = false,
    val enableBenchmarkMode: Boolean = false
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

