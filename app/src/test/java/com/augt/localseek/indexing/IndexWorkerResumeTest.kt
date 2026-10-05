package com.augt.localseek.indexing

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class IndexWorkerResumeTest {

    @Test
    fun `resume work request has 15 minute initial delay`() {
        val request = IndexWorker.createResumeWorkRequest()
        val expectedDelayMs = TimeUnit.MINUTES.toMillis(IndexWorker.RESUME_DELAY_MINUTES)
        assertEquals(expectedDelayMs, request.workSpec.initialDelay)
        assertEquals(900_000L, request.workSpec.initialDelay)
    }

    @Test
    fun `resume work request enforces requiresBatteryNotLow constraint`() {
        val request = IndexWorker.createResumeWorkRequest()
        val constraints = request.workSpec.constraints
        assertTrue("Resume request must require battery not low", constraints.requiresBatteryNotLow())
    }

    @Test
    fun `resume work request is strictly not expedited to prevent dataSync timeout loop`() {
        val request = IndexWorker.createResumeWorkRequest()
        assertFalse("Resume request must NOT be expedited", request.workSpec.expedited)
    }

    @Test
    fun `resume work request configures non-force incremental resume payload`() {
        val request = IndexWorker.createResumeWorkRequest()
        val input = request.workSpec.input
        assertFalse("Force reindex must be false for resume", input.getBoolean(IndexWorker.IN_FORCE_REINDEX, true))
        assertTrue("is_resume must be true for resume", input.getBoolean(IndexWorker.IN_IS_RESUME, false))
    }

    @Test
    fun `resume policy is not KEEP because the stopping worker is still running under index_once`() {
        assertNotEquals(
            "KEEP drops the resume while the stopping worker is still RUNNING",
            ExistingWorkPolicy.KEEP,
            IndexWorker.RESUME_WORK_POLICY
        )
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, IndexWorker.RESUME_WORK_POLICY)
    }

    @Test
    fun `resume is queued for both timeout stop reasons only`() {
        assertTrue(IndexWorker.needsResume(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
        assertTrue(IndexWorker.needsResume(WorkInfo.STOP_REASON_TIMEOUT))
        assertFalse(IndexWorker.needsResume(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertFalse(IndexWorker.needsResume(WorkInfo.STOP_REASON_NOT_STOPPED))
    }

    @Test
    fun `resume work request tags contain standard IndexWorker identifiers`() {
        val request = IndexWorker.createResumeWorkRequest()
        assertTrue(request.tags.contains(IndexWorker.TAG))
        assertTrue(request.tags.contains(IndexWorker.TAG_ONE_TIME))
    }
}
