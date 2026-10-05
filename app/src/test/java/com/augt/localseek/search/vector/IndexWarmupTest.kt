package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkEmbedding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IndexWarmupTest {

    private class FakeIndex(private val failWith: Throwable? = null) : VectorIndex {
        var calls = 0
        var lastQuerySize = -1
        override val backendName = "fake"
        override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {}
        override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
            calls++
            lastQuerySize = queryVec.size
            failWith?.let { throw it }
            return emptyList()
        }
    }

    @Test
    fun `runs one dummy search of the right dimension after the start delay`() = runTest {
        val index = FakeIndex()
        val job = launch { IndexWarmup(index, dim = 384, startDelayMs = 1_000L).run() }
        runCurrent()
        assertEquals("nothing before the delay", 0, index.calls)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0, index.calls)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, index.calls)
        assertEquals(384, index.lastQuerySize)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `a failing index is swallowed and reported as false`() = runTest {
        assertFalse(IndexWarmup(FakeIndex(IllegalStateException("boom")), startDelayMs = 0L).run())
        assertTrue(IndexWarmup(FakeIndex(), startDelayMs = 0L).run())
    }

    @Test
    fun `cancellation propagates and is not swallowed`() = runTest {
        var cancelled = false
        try {
            IndexWarmup(FakeIndex(CancellationException("stop")), startDelayMs = 0L).run()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `cancelling during the start delay never touches the index`() = runTest {
        val index = FakeIndex()
        val job = launch { IndexWarmup(index, startDelayMs = 5_000L).run() }
        runCurrent()
        job.cancel()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, index.calls)
    }
}
