package com.augt.localseek.indexing

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "IndexWorker"
        const val OUT_NEW_FILES = "new_files"
        const val OUT_UPDATED_FILES = "updated_files"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background indexing run...")
        val encoder = com.augt.localseek.ml.DenseEncoder(applicationContext)

        return try {
            val indexer = FileIndexer(applicationContext, encoder)
            val stats = indexer.runFullIndex()

            Log.d(TAG, "Indexing complete! Stats: $stats")

            val outputData = workDataOf(
                OUT_NEW_FILES to stats.newFiles,
                OUT_UPDATED_FILES to stats.updatedFiles
            )

            Result.success(outputData)
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            encoder.close()
        }
    }
}