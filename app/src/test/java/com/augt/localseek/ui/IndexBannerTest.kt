package com.augt.localseek.ui

import androidx.work.WorkInfo
import com.augt.localseek.notify.IndexProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBannerTest {

    private val tag = "com.augt.localseek.indexing.IndexWorker"
    private val oneTime = setOf(tag, IndexingStatusMapper.TAG_ONE_TIME)
    private val periodic = setOf(tag, "index_periodic")

    private fun running(p: IndexProgress? = null) = IndexWorkSnapshot(WorkInfo.State.RUNNING, oneTime, p)

    // ---- observation from WorkManager ----

    @Test fun `running work is observed with its progress`() {
        val obs = IndexBanner.observe(listOf(running(IndexProgress(10, 100)), IndexWorkSnapshot(WorkInfo.State.ENQUEUED, periodic)))
        assertEquals(IndexObservation.Running(IndexProgress(10, 100)), obs)
    }

    @Test fun `enqueued one time work is queued but the periodic one is not`() {
        assertEquals(IndexObservation.Queued, IndexBanner.observe(listOf(IndexWorkSnapshot(WorkInfo.State.ENQUEUED, oneTime))))
        assertEquals(
            IndexObservation.Idle(hadFailure = false, hadSuccess = false),
            IndexBanner.observe(listOf(IndexWorkSnapshot(WorkInfo.State.ENQUEUED, periodic)))
        )
    }

    // ---- transitions: running -> done -> hidden ----

    @Test fun `running then finished shows up to date then hides`() {
        var state: IndexBannerState = IndexBannerState.Hidden
        state = IndexBanner.reduce(state, IndexObservation.Running(IndexProgress(5990, 6000)))
        assertEquals(IndexBannerState.Running(IndexProgress(5990, 6000)), state)

        state = IndexBanner.reduce(state, IndexObservation.Idle(hadFailure = false, hadSuccess = true))
        assertEquals(IndexBannerState.UpToDate(5990), state)
        assertTrue(IndexBanner.isVisible(state))

        // more idle observations while the 3 s timer runs keep the message
        state = IndexBanner.reduce(state, IndexObservation.Idle(false, true))
        assertTrue(state is IndexBannerState.UpToDate)

        state = IndexBanner.dismissUpToDate(state)
        assertEquals(IndexBannerState.Hidden, state)
        assertFalse(IndexBanner.isVisible(state))
    }

    @Test fun `up to date lasts three seconds`() {
        assertEquals(3_000L, IndexBanner.UP_TO_DATE_MS)
    }

    @Test fun `old successful work at app start does not show the banner`() {
        val state = IndexBanner.reduce(IndexBannerState.Hidden, IndexObservation.Idle(hadFailure = false, hadSuccess = true))
        assertEquals(IndexBannerState.Hidden, state)
    }

    @Test fun `dismiss only affects the up to date state`() {
        val running = IndexBannerState.Running(null)
        assertEquals(running, IndexBanner.dismissUpToDate(running))
        assertEquals(IndexBannerState.Failed, IndexBanner.dismissUpToDate(IndexBannerState.Failed))
    }

    // ---- failure -> resume ----

    @Test fun `failed run shows the failure state with resume`() {
        var state: IndexBannerState = IndexBanner.reduce(IndexBannerState.Hidden, IndexObservation.Running(null))
        state = IndexBanner.reduce(state, IndexObservation.Idle(hadFailure = true, hadSuccess = false))
        assertEquals(IndexBannerState.Failed, state)
        // resume: a new run starts, then it completes
        state = IndexBanner.reduce(state, IndexObservation.Running(IndexProgress(1, 10)))
        assertTrue(state is IndexBannerState.Running)
        state = IndexBanner.reduce(state, IndexObservation.Idle(hadFailure = true, hadSuccess = true))
        assertTrue(state is IndexBannerState.UpToDate)
    }

    @Test fun `a lone failure is shown on app start so it can be resumed`() {
        val state = IndexBanner.reduce(IndexBannerState.Hidden, IndexObservation.Idle(hadFailure = true, hadSuccess = false))
        assertEquals(IndexBannerState.Failed, state)
    }

    @Test fun `failure persists until work runs again`() {
        val state = IndexBanner.reduce(IndexBannerState.Failed, IndexObservation.Idle(hadFailure = true, hadSuccess = false))
        assertEquals(IndexBannerState.Failed, state)
    }

    @Test fun `queued resume after a timeout shows queued with a run now action`() {
        var state: IndexBannerState = IndexBannerState.Running(IndexProgress(100, 200))
        state = IndexBanner.reduce(state, IndexObservation.Queued)
        assertEquals(IndexBannerState.Queued, state)
        state = IndexBanner.reduce(state, IndexObservation.Idle(false, false))
        assertEquals(IndexBannerState.Hidden, state)
    }
}
