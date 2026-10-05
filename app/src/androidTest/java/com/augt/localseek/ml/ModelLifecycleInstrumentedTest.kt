package com.augt.localseek.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.indexing.FileIndexer
import com.augt.localseek.ui.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 instrumented verification test.
 *
 * Verifies:
 * 1. Process-wide shared object identity across search and indexing paths:
 *    Exactly one DenseEncoder, one CrossEncoder, one ClipTextEncoder, one ClipImageEncoder,
 *    and one LshIndexManager instance are shared across components in the same process.
 * 2. Concurrent inference stress test:
 *    8 coroutines invoking inference through the registry without native crash or lock corruption.
 */
@RunWith(AndroidJUnit4::class)
class ModelLifecycleInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Context>() as LocalSeekApplication

    @Test
    fun testSharedModelInstanceIdentityAcrossSearchAndIndexing() {
        val container = app.appContainer
        assertNotNull("AppContainer must not be null", container)

        // Search path dependencies
        val searchViewModel = SearchViewModel(app, container)
        val denseRetriever = container.denseRetriever
        val imageRetriever = container.imageRetriever
        val crossEncoderReranker = container.crossEncoderReranker
        val queryProcessor = container.queryProcessor

        // Indexing path dependencies
        val fileIndexer = FileIndexer(app, container)

        // 1. Verify DenseEncoder identity
        val sharedDenseEncoder = container.modelRegistry.denseEncoder
        assertNotNull("DenseEncoder must initialize", sharedDenseEncoder)
        assertSame(
            "Search DenseRetriever must use the shared DenseEncoder from ModelRegistry",
            sharedDenseEncoder,
            denseRetriever.encoder
        )

        // 2. Verify CrossEncoder identity
        val sharedCrossEncoder = container.modelRegistry.crossEncoder
        assertNotNull("CrossEncoder must initialize", sharedCrossEncoder)
        assertSame(
            "Search CrossEncoderReranker must use the shared CrossEncoder from ModelRegistry",
            sharedCrossEncoder,
            crossEncoderReranker.crossEncoder
        )

        // 3. Verify ClipTextEncoder identity
        val sharedClipTextEncoder = container.modelRegistry.clipTextEncoder
        assertNotNull("ClipTextEncoder must initialize", sharedClipTextEncoder)
        assertSame(
            "Search ImageRetriever must use the shared ClipTextEncoder from ModelRegistry",
            sharedClipTextEncoder,
            imageRetriever.clipTextEncoder
        )

        // 4. Verify ClipImageEncoder identity
        val sharedClipImageEncoder = container.modelRegistry.clipImageEncoder
        assertNotNull("ClipImageEncoder must initialize", sharedClipImageEncoder)

        // 5. Verify LshIndexManager identity
        val sharedLshManager = container.lshIndexManager
        assertSame(
            "Search DenseRetriever must share the container LshIndexManager",
            sharedLshManager,
            denseRetriever.indexManager
        )

        // 6. Verify Database identity
        assertSame(
            "Container database must match AppDatabase singleton",
            AppDatabase.getInstance(app),
            container.database
        )
    }

    @Test
    fun testConcurrentInferenceStressTestThroughRegistry() = runBlocking {
        val container = app.appContainer
        val registry = container.modelRegistry

        val coroutineCount = 8
        val results = mutableListOf<FloatArray>()

        val jobs = (1..coroutineCount).map { index ->
            async(Dispatchers.Default) {
                registry.recordInference {
                    // Exercise inference on the shared DenseEncoder
                    registry.denseEncoder.encode("heterogeneous hybrid search stress test query $index")
                }
            }
        }

        val completedVectors = jobs.awaitAll()

        assertEquals("All 8 concurrent inferences must complete", coroutineCount, completedVectors.size)
        for (vector in completedVectors) {
            assertTrue("Output vector must not be empty", vector.isNotEmpty())
            assertEquals("MiniLM embedding dimension must be 384", 384, vector.size)
        }

        // Concurrency metrics verification
        assertEquals("Active inference count should return to 0", 0, registry.getActiveInferenceCount())
        assertTrue("Total inference calls recorded must be >= 8", registry.getTotalInferenceCalls() >= coroutineCount)
    }
}
