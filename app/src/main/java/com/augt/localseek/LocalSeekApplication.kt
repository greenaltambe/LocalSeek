package com.augt.localseek

import android.app.Application
import android.util.Log
import com.augt.localseek.search.rag.RAGEngine
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

        applicationScope.launch {
            val ready = ragEngine.initialize()
            if (ready) {
                Log.d(TAG, "RAG engine ready")
            } else {
                Log.w(TAG, "RAG engine unavailable, search-only mode remains active")
            }
        }
    }
}

