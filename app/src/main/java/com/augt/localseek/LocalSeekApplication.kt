package com.augt.localseek

import android.app.Application
import android.util.Log
import com.augt.localseek.search.rag.RAGEngine
import com.augt.localseek.indexing.IndexScheduler
import com.augt.localseek.ui.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalSeekApplication : Application() {

    companion object {
        private const val TAG = "LocalSeekApp"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val ragEngine: RAGEngine by lazy { RAGEngine(this) }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LocalSeek application starting")

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

            val ready = ragEngine.initialize()
            if (ready) {
                Log.d(TAG, "RAG engine ready")
            } else {
                Log.w(TAG, "RAG engine unavailable, search-only mode remains active")
            }
        }
    }
}

