package com.augt.localseek.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.ml.clip.ClipAssetPackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _indexStats = MutableStateFlow(IndexStats())
    val indexStats: StateFlow<IndexStats> = _indexStats.asStateFlow()

    private val _benchmarkCount = MutableStateFlow(0)
    val benchmarkCount: StateFlow<Int> = _benchmarkCount.asStateFlow()

    val clipPackState = ClipAssetPackManager.packState

    init {
        ClipAssetPackManager.refreshStatus(application)
        viewModelScope.launch {
            repository.settings.collect { saved ->
                _settings.value = saved
            }
        }
        refreshIndexStats()
        refreshBenchmarkCount()
    }

    fun downloadClipPack() {
        ClipAssetPackManager.startDownload(getApplication())
    }

    fun retryClipPackDownload() {
        ClipAssetPackManager.startDownload(getApplication())
    }

    fun cancelClipPackDownload() {
        ClipAssetPackManager.cancelDownload(getApplication())
    }

    fun updateSetting(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            repository.update(transform)
            _settings.update { current -> current.transform() }
        }
    }

    fun rebuildIndex(forceAll: Boolean = false) {
        IndexScheduler.scheduleImmediateIndex(getApplication(), forceAll)
        refreshIndexStats()
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.reset()
            _settings.value = AppSettings()
            _indexStats.value = IndexStats()
        }
    }

    fun refreshIndexStats() {
        viewModelScope.launch {
            _indexStats.value = repository.indexStats()
        }
    }

    fun refreshBenchmarkCount() {
        viewModelScope.launch {
            _benchmarkCount.value = AppDatabase.getInstance(getApplication()).benchmarkRunDao().getCount()
        }
    }

    fun clearBenchmarkData() {
        viewModelScope.launch {
            AppDatabase.getInstance(getApplication()).benchmarkRunDao().clearAll()
            _benchmarkCount.value = 0
        }
    }

    suspend fun exportBenchmarkCsv(): File {
        return BenchmarkLogger.exportToCsv(getApplication())
    }

    suspend fun exportBenchmarkJson(): File {
        return BenchmarkLogger.exportToJson(getApplication())
    }
}
