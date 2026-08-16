package com.augt.localseek.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.ml.llm.DownloadProgress
import com.augt.localseek.ml.llm.LLMCapabilities
import com.augt.localseek.ml.llm.LLMDiagnostics
import com.augt.localseek.ml.llm.LLMProvider
import com.augt.localseek.ml.llm.ModelDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val llmProvider = LLMProvider(application)
    private val modelDownloader = ModelDownloader(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _indexStats = MutableStateFlow(IndexStats())
    val indexStats: StateFlow<IndexStats> = _indexStats.asStateFlow()

    private val _llmCapabilities = MutableStateFlow(
        LLMCapabilities(
            name = "None",
            provider = "N/A",
            maxTokens = 0,
            estimatedLatency = 0,
            supportsStreaming = false,
            memoryImpact = "N/A",
            isAvailable = false,
            requiresDownload = true
        )
    )
    val llmCapabilities: StateFlow<LLMCapabilities> = _llmCapabilities.asStateFlow()

    private val _llmDiagnostics = MutableStateFlow(
        LLMDiagnostics(
            sdkVersion = android.os.Build.VERSION.SDK_INT,
            androidVersion = android.os.Build.VERSION.RELEASE,
            aiCoreFound = false,
            phi3Found = false,
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            geminiReason = "Not checked",
            phi3Reason = "Not checked",
            detectedAiCorePackage = null,
            phi3JniReady = false,
            summary = "Not checked"
        )
    )
    val llmDiagnostics: StateFlow<LLMDiagnostics> = _llmDiagnostics.asStateFlow()

    private val _isPhi3Downloaded = MutableStateFlow(modelDownloader.isPhi3Downloaded())
    val isPhi3Downloaded: StateFlow<Boolean> = _isPhi3Downloaded.asStateFlow()

    private val _phi3DownloadState = MutableStateFlow<DownloadState>(
        if (_isPhi3Downloaded.value) DownloadState.Completed else DownloadState.NotStarted
    )
    val phi3DownloadState: StateFlow<DownloadState> = _phi3DownloadState.asStateFlow()

    private val _benchmarkCount = MutableStateFlow(0)
    val benchmarkCount: StateFlow<Int> = _benchmarkCount.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { saved ->
                _settings.value = saved
            }
        }
        refreshIndexStats()
        refreshLlmCapabilities()
        refreshBenchmarkCount()
    }

    fun updateSetting(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            repository.update(transform)
            _settings.update { current -> current.transform() }
        }
    }

    fun updateGeminiApiKey(key: String) {
        viewModelScope.launch {
            repository.updateGeminiApiKey(key)
            _settings.update { current -> current.copy(geminiApiKey = key.trim()) }
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
            refreshLlmCapabilities()
        }
    }

    fun refreshIndexStats() {
        viewModelScope.launch {
            _indexStats.value = repository.indexStats()
        }
    }

    fun refreshLlmCapabilities() {
        _llmCapabilities.value = llmProvider.getCapabilities()
        _llmDiagnostics.value = llmProvider.getDiagnostics()
        val downloaded = modelDownloader.isPhi3Downloaded()
        _isPhi3Downloaded.value = downloaded
        if (downloaded && _phi3DownloadState.value !is DownloadState.Downloading) {
            _phi3DownloadState.value = DownloadState.Completed
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

    fun downloadPhi3() {
        if (_phi3DownloadState.value is DownloadState.Downloading) return
        viewModelScope.launch {
            modelDownloader.downloadPhi3().collect { progress ->
                when (progress) {
                    DownloadProgress.Starting -> _phi3DownloadState.value = DownloadState.Downloading(0)
                    is DownloadProgress.Downloading -> _phi3DownloadState.value = DownloadState.Downloading(progress.percent)
                    is DownloadProgress.Completed -> {
                        _phi3DownloadState.value = DownloadState.Completed
                        _isPhi3Downloaded.value = true
                        refreshLlmCapabilities()
                        (getApplication<Application>() as LocalSeekApplication).ragEngine.initialize()
                    }

                    is DownloadProgress.Failed -> {
                        _phi3DownloadState.value = DownloadState.Failed(progress.error)
                    }
                }
            }
        }
    }
}
