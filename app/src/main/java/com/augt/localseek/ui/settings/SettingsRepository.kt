package com.augt.localseek.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.augt.localseek.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

class SettingsRepository(private val context: Context) {

    private val database by lazy { AppDatabase.getInstance(context) }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { pref ->
        AppSettings(
            enableDenseRetrieval = pref[Keys.ENABLE_DENSE] ?: true,
            enableReranking = pref[Keys.ENABLE_RERANKING] ?: true,
            enableQueryExpansion = pref[Keys.ENABLE_QUERY_EXPANSION] ?: true,
            geminiApiKey = pref[Keys.GEMINI_API_KEY] ?: "",
            maxResults = pref[Keys.MAX_RESULTS] ?: 20,
            batteryAwareMode = pref[Keys.BATTERY_AWARE] ?: true,
            adaptiveLsh = pref[Keys.ADAPTIVE_LSH] ?: true,
            memoryMode = MemoryMode.valueOf(pref[Keys.MEMORY_MODE] ?: MemoryMode.AUTO.name),
            chunkSize = pref[Keys.CHUNK_SIZE] ?: 150,
            chunkOverlap = pref[Keys.CHUNK_OVERLAP] ?: 40,
            autoReindex = pref[Keys.AUTO_REINDEX] ?: false,
            showDebugInfo = pref[Keys.SHOW_DEBUG] ?: false,
            verboseLogging = pref[Keys.VERBOSE_LOGGING] ?: false,
            enablePerTypeNormalization = pref[Keys.ENABLE_PER_TYPE_NORM] ?: false,
            enableBenchmarkMode = pref[Keys.ENABLE_BENCHMARK] ?: false
        )
    }

    suspend fun update(transform: AppSettings.() -> AppSettings) {
        val updated = transform(settings.first())
        context.settingsDataStore.edit { pref ->
            pref[Keys.ENABLE_DENSE] = updated.enableDenseRetrieval
            pref[Keys.ENABLE_RERANKING] = updated.enableReranking
            pref[Keys.ENABLE_QUERY_EXPANSION] = updated.enableQueryExpansion
            pref[Keys.GEMINI_API_KEY] = updated.geminiApiKey
            pref[Keys.MAX_RESULTS] = updated.maxResults
            pref[Keys.BATTERY_AWARE] = updated.batteryAwareMode
            pref[Keys.ADAPTIVE_LSH] = updated.adaptiveLsh
            pref[Keys.MEMORY_MODE] = updated.memoryMode.name
            pref[Keys.CHUNK_SIZE] = updated.chunkSize
            pref[Keys.CHUNK_OVERLAP] = updated.chunkOverlap
            pref[Keys.AUTO_REINDEX] = updated.autoReindex
            pref[Keys.SHOW_DEBUG] = updated.showDebugInfo
            pref[Keys.VERBOSE_LOGGING] = updated.verboseLogging
            pref[Keys.ENABLE_PER_TYPE_NORM] = updated.enablePerTypeNormalization
            pref[Keys.ENABLE_BENCHMARK] = updated.enableBenchmarkMode
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }

    suspend fun updateGeminiApiKey(key: String) {
        context.settingsDataStore.edit { pref ->
            pref[Keys.GEMINI_API_KEY] = key.trim()
        }
    }

    suspend fun hasAppliedTitleFix(): Boolean {
        return context.settingsDataStore.data.map { it[Keys.TITLE_INDEXING_FIX_APPLIED] ?: false }.first()
    }

    suspend fun markTitleFixApplied() {
        context.settingsDataStore.edit { it[Keys.TITLE_INDEXING_FIX_APPLIED] = true }
    }

    suspend fun indexStats(): IndexStats {
        val documents = database.documentDao().getDocumentCount()
        val chunks = database.chunkDao().countAllChunks()
        val lastUpdated = database.documentDao().getLastUpdatedTimestamp()
        val dbFile = context.getDatabasePath("hybrid_search.db")
        val indexSize = if (dbFile.exists()) dbFile.length() else 0L
        return IndexStats(
            totalFiles = documents,
            totalChunks = chunks,
            indexSizeBytes = indexSize,
            lastUpdated = lastUpdated,
            isHealthy = documents > 0 && chunks >= documents
        )
    }

    private object Keys {
        val ENABLE_DENSE = booleanPreferencesKey("enable_dense")
        val ENABLE_RERANKING = booleanPreferencesKey("enable_reranking")
        val ENABLE_QUERY_EXPANSION = booleanPreferencesKey("enable_query_expansion")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val MAX_RESULTS = intPreferencesKey("max_results")
        val BATTERY_AWARE = booleanPreferencesKey("battery_aware")
        val ADAPTIVE_LSH = booleanPreferencesKey("adaptive_lsh")
        val MEMORY_MODE = stringPreferencesKey("memory_mode")
        val CHUNK_SIZE = intPreferencesKey("chunk_size")
        val CHUNK_OVERLAP = intPreferencesKey("chunk_overlap")
        val AUTO_REINDEX = booleanPreferencesKey("auto_reindex")
        val SHOW_DEBUG = booleanPreferencesKey("show_debug")
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging")
        val ENABLE_PER_TYPE_NORM = booleanPreferencesKey("enable_per_type_norm")
        val ENABLE_BENCHMARK = booleanPreferencesKey("enable_benchmark")
        val TITLE_INDEXING_FIX_APPLIED = booleanPreferencesKey("title_indexing_fix_applied")
    }
}

