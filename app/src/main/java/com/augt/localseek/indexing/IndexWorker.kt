package com.augt.localseek.indexing

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.notify.IndexNotifier
import com.augt.localseek.notify.IndexProgress
import com.augt.localseek.notify.IndexProgressKeys
import com.augt.localseek.notify.IndexProgressSource
import com.augt.localseek.tools.IndexNotificationMode
import com.augt.localseek.tools.ToolsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "IndexWorker"
        const val OUT_NEW_FILES = "new_files"
        const val OUT_UPDATED_FILES = "updated_files"
        /** Non-empty when the deletion step was skipped to protect the index (e.g. "index kept: storage access changed"). */
        const val OUT_RECONCILE_SKIPPED = "reconcile_skipped"
        const val IN_FORCE_REINDEX = "force_reindex"
        const val IN_IS_RESUME = "is_resume"
        const val TAG_ONE_TIME = "index_once"
        const val RESUME_DELAY_MINUTES = 15L
        private const val PROGRESS_INTERVAL_MS = 2_000L

        /**
         * The stopping worker is itself still RUNNING (unfinished) under the unique name `index_once`
         * while it calls rescheduleResume(), so KEEP would silently drop the resume request.
         * APPEND_OR_REPLACE chains the resume after the current run instead, and replaces the chain
         * if the previous run ended failed/cancelled.
         */
        val RESUME_WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

        /** Stop reasons after which an explicit resume must be queued. */
        fun needsResume(stopReason: Int): Boolean =
            stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ||
                stopReason == WorkInfo.STOP_REASON_TIMEOUT

        /**
         * Builds a resume work request with a 15-minute backoff delay and battery-not-low constraint,
         * running strictly as a background worker (no foreground/expedited status) to prevent
         * immediately re-triggering the foreground service dataSync timeout.
         */
        fun createResumeWorkRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<IndexWorker>()
                .addTag(TAG)
                .addTag(TAG_ONE_TIME)
                .setInputData(
                    workDataOf(
                        IN_FORCE_REINDEX to false,
                        IN_IS_RESUME to true
                    )
                )
                .setInitialDelay(RESUME_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting background indexing run...")
        val forceReindex = inputData.getBoolean(IN_FORCE_REINDEX, false)
        val isResume = inputData.getBoolean(IN_IS_RESUME, false)
        val notificationMode = try {
            ToolsRepository(applicationContext).uiPrefs.first().notificationMode
        } catch (e: Exception) {
            IndexNotificationMode.PROGRESS_AND_DONE
        }

        var isForeground = false
        if (!isResume) {
            try {
                setForeground(createForegroundInfo(null, notificationMode))
                isForeground = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not set foreground mode for IndexWorker (continuing as background worker): ${e.message}")
            }
        } else {
            Log.d(TAG, "IndexWorker running as background resume task; foreground service mode omitted.")
        }

        return try {
            val app = applicationContext as LocalSeekApplication
            val container = app.appContainer
            val indexer = FileIndexer(applicationContext, container)
            val stats = withProgressUpdates(container.database, isForeground, notificationMode) {
                indexer.runFullIndex(forceReindex)
            }

            Log.d(TAG, "Indexing complete! Stats: $stats")
            if (notificationMode == IndexNotificationMode.PROGRESS_AND_DONE && stats.newFiles + stats.updatedFiles > 0) {
                IndexNotifier.postDone(applicationContext, container.database.documentDao().getDocumentCount())
            }

            val outputData = workDataOf(
                OUT_NEW_FILES to stats.newFiles,
                OUT_UPDATED_FILES to stats.updatedFiles,
                OUT_RECONCILE_SKIPPED to (stats.reconcileSkipped ?: "")
            )

            Result.success(outputData)
        } catch (e: CancellationException) {
            // getStopReason() is API 31+; the timeouts we resume from only exist on newer releases.
            val reason = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) stopReason else WorkInfo.STOP_REASON_UNKNOWN
            Log.w(TAG, "IndexWorker cancelled/stopped with stopReason=$reason", e)
            if (needsResume(reason)) {
                Log.i(TAG, "Worker timed out (stopReason=$reason); rescheduling index resume.")
                rescheduleResume()
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun rescheduleResume() {
        try {
            val request = createResumeWorkRequest()

            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    TAG_ONE_TIME,
                    RESUME_WORK_POLICY,
                    request
                )
            Log.i(TAG, "Successfully enqueued resume IndexWorker job via $RESUME_WORK_POLICY with 15-minute initial delay.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule IndexWorker on timeout", e)
        }
    }

    /**
     * Runs [block] (the unchanged indexer) while a sibling coroutine publishes progress every 2 s: WorkManager
     * progress data for the in-app banner and, when running in the foreground, the notification. The indexer is
     * not touched; progress is derived from the index and a scan estimate (see [IndexProgressSource]).
     */
    private suspend fun <T> withProgressUpdates(
        database: com.augt.localseek.data.AppDatabase,
        isForeground: Boolean,
        mode: IndexNotificationMode,
        block: suspend () -> T
    ): T = coroutineScope {
        val source = IndexProgressSource(applicationContext, database)
        val poller = launch {
            try {
                val total = source.estimateTotal()
                while (isActive) {
                    val progress = IndexProgress(source.indexedCount(), total)
                    setProgress(workDataOf(IndexProgressKeys.DONE to progress.done, IndexProgressKeys.TOTAL to progress.total))
                    if (isForeground && mode != IndexNotificationMode.MINIMAL) {
                        try {
                            setForeground(createForegroundInfo(progress, mode))
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not update indexing notification: ${e.message}")
                        }
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Progress reporting stopped: ${e.message}")
            }
        }
        try {
            block()
        } finally {
            poller.cancel()
        }
    }

    private fun createForegroundInfo(progress: IndexProgress?, mode: IndexNotificationMode): androidx.work.ForegroundInfo {
        IndexNotifier.ensureChannels(applicationContext)
        val notification = IndexNotifier.progressNotification(applicationContext, id, progress, mode)
        val notificationId = IndexNotifier.ID_PROGRESS

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(notificationId, notification)
        }
    }
}
