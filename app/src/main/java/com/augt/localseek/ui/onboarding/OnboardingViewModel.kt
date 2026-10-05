package com.augt.localseek.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.BuildConfig
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.tools.ToolsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connects the pure [OnboardingState] to persistence and to the existing image pack manager.
 * Permission requests are NOT made here: the activity owns the launchers (see [OnboardingPermissions]).
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ToolsRepository(application)

    private val _state = MutableStateFlow(OnboardingState.create(includePhotos = BuildConfig.ENABLE_IMAGE_SEARCH))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    val packState = ClipAssetPackManager.packState

    init {
        ClipAssetPackManager.refreshStatus(application)
    }

    fun next() = _state.update { it.next() }
    fun back() = _state.update { it.back() }
    fun skipAll() = _state.update { it.skipAll() }

    /** Starts the tour from the first page again (used by "Replay onboarding"). */
    fun restart() {
        _state.value = OnboardingState.create(includePhotos = BuildConfig.ENABLE_IMAGE_SEARCH)
    }

    /** Persists that the tour was completed or skipped so it is not shown again on the next start. */
    fun markCompleted() {
        viewModelScope.launch { repository.setOnboardingCompleted(true) }
    }

    fun downloadPack() = ClipAssetPackManager.startDownload(getApplication())
    fun cancelPackDownload() = ClipAssetPackManager.cancelDownload(getApplication())
}
