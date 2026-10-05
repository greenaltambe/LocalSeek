package com.augt.localseek.ui

import androidx.work.WorkInfo
import com.augt.localseek.notify.IndexProgress

/**
 * Minimal view of a WorkManager [WorkInfo] so the label mapping stays unit-testable. [progress] is what the running
 * worker last published (null until the first update).
 */
data class IndexWorkSnapshot(val state: WorkInfo.State, val tags: Set<String>, val progress: IndexProgress? = null)

data class IndexingStatus(val isIndexing: Boolean, val label: String?)

object IndexingStatusMapper {
    /**
     * Tag of the one-time index request. Mirrors the private constant in
     * [com.augt.localseek.indexing.IndexScheduler], which is frozen and cannot expose it.
     */
    const val TAG_ONE_TIME = "index_once"

    const val LABEL_RUNNING = "Indexing content in background..."
    const val LABEL_QUEUED = "Indexing queued..."

    val IDLE = IndexingStatus(isIndexing = false, label = null)

    /**
     * RUNNING work of any kind means indexing is in progress. ENQUEUED only counts as
     * "queued" for the one-time request: the 6-hour periodic request sits in ENQUEUED
     * between runs, so treating it as queued would show the label forever.
     */
    fun map(works: List<IndexWorkSnapshot>): IndexingStatus = when {
        works.any { it.state == WorkInfo.State.RUNNING } ->
            IndexingStatus(isIndexing = true, label = LABEL_RUNNING)
        works.any { it.state == WorkInfo.State.ENQUEUED && TAG_ONE_TIME in it.tags } ->
            IndexingStatus(isIndexing = true, label = LABEL_QUEUED)
        else -> IDLE
    }
}
