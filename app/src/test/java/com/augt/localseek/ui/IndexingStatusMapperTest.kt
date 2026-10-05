package com.augt.localseek.ui

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexingStatusMapperTest {

    private val indexTag = "com.augt.localseek.indexing.IndexWorker"
    private val oneTime = setOf(indexTag, IndexingStatusMapper.TAG_ONE_TIME)
    private val periodic = setOf(indexTag, "index_periodic")

    @Test
    fun `running work shows running label`() {
        val status = IndexingStatusMapper.map(
            listOf(
                IndexWorkSnapshot(WorkInfo.State.RUNNING, oneTime),
                IndexWorkSnapshot(WorkInfo.State.ENQUEUED, periodic)
            )
        )
        assertEquals(IndexingStatus(true, IndexingStatusMapper.LABEL_RUNNING), status)
    }

    @Test
    fun `running periodic work shows running label`() {
        val status = IndexingStatusMapper.map(listOf(IndexWorkSnapshot(WorkInfo.State.RUNNING, periodic)))
        assertEquals(IndexingStatus(true, IndexingStatusMapper.LABEL_RUNNING), status)
    }

    @Test
    fun `enqueued one-time work shows queued label`() {
        val status = IndexingStatusMapper.map(
            listOf(
                IndexWorkSnapshot(WorkInfo.State.ENQUEUED, oneTime),
                IndexWorkSnapshot(WorkInfo.State.ENQUEUED, periodic)
            )
        )
        assertEquals(IndexingStatus(true, IndexingStatusMapper.LABEL_QUEUED), status)
    }

    @Test
    fun `periodic-only enqueued work is idle`() {
        val status = IndexingStatusMapper.map(
            listOf(
                IndexWorkSnapshot(WorkInfo.State.SUCCEEDED, oneTime),
                IndexWorkSnapshot(WorkInfo.State.ENQUEUED, periodic)
            )
        )
        assertEquals(IndexingStatusMapper.IDLE, status)
    }

    @Test
    fun `no work is idle`() {
        assertEquals(IndexingStatusMapper.IDLE, IndexingStatusMapper.map(emptyList()))
    }
}
