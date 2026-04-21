package com.augt.localseek.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.indexing.IndexScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _indexStats = MutableStateFlow(IndexStats())
    val indexStats: StateFlow<IndexStats> = _indexStats.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { saved ->
                _settings.value = saved
            }
        }
        refreshIndexStats()
    }

    fun updateSetting(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            repository.update(transform)
            _settings.update { current -> current.transform() }
        }
    }

    fun rebuildIndex() {
        IndexScheduler.scheduleImmediateIndex(getApplication())
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
}

