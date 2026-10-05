package com.augt.localseek

import android.app.Application
import android.util.Log
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.augt.localseek.di.AppContainer
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.search.vector.IndexWarmup
import com.augt.localseek.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalSeekApplication : Application() {

    companion object {
        private const val TAG = "LocalSeekApp"

        fun from(context: Context): LocalSeekApplication {
            return context.applicationContext as LocalSeekApplication
        }

        fun getContainer(context: Context): AppContainer {
            return from(context).appContainer
        }
    }

    @VisibleForTesting
    var appContainerOverride: AppContainer? = null

    val appContainer: AppContainer
        get() = appContainerOverride ?: _appContainer ?: synchronized(this) {
            _appContainer ?: AppContainer(this).also { _appContainer = it }
        }

    @Volatile
    private var _appContainer: AppContainer? = null

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LocalSeek application starting")

        if (_appContainer == null) {
            _appContainer = AppContainer(this)
        }

        val settingsRepo = SettingsRepository(this)

        applicationScope.launch {
            val stats = settingsRepo.indexStats()
            if (stats.totalFiles > 0 && !settingsRepo.hasAppliedTitleFix()) {
                Log.i(TAG, "Upgrade detected. Triggering one-time mandatory re-index for BM25 title fix...")
                IndexScheduler.scheduleImmediateIndex(this@LocalSeekApplication, forceAll = true)
                settingsRepo.markTitleFixApplied()
            } else if (stats.totalFiles == 0) {
                // For fresh installs, we don't need the "title fix" re-index as 
                // MainActivity will trigger a full initial index.
                settingsRepo.markTitleFixApplied()
            }
        }

        // Load the dense index (exact in memory, or the binary one above 50,000 chunks) once in the background so the first
        // search avoids the cold load.
        applicationScope.launch(Dispatchers.Default) {
            IndexWarmup(appContainer.autoVectorIndex).run()
        }
    }
}
