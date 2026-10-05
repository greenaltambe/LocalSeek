package com.augt.localseek.ml

import android.content.Context
import com.augt.localseek.ml.clip.ClipImageEncoder
import com.augt.localseek.ml.clip.ClipTextEncoder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class ModelRegistryTest {

    private lateinit var mockContext: Context
    private lateinit var mockDenseEncoder: DenseEncoder
    private lateinit var mockCrossEncoder: CrossEncoder
    private lateinit var mockClipTextEncoder: ClipTextEncoder
    private lateinit var mockClipImageEncoder: ClipImageEncoder

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockDenseEncoder = mockk(relaxed = true)
        mockCrossEncoder = mockk(relaxed = true)
        mockClipTextEncoder = mockk(relaxed = true)
        mockClipImageEncoder = mockk(relaxed = true)
    }

    @Test
    fun testModelRegistrySingleInstanceGuarantee() {
        val registry = ModelRegistry(
            context = mockContext,
            denseEncoder = mockDenseEncoder,
            crossEncoder = mockCrossEncoder,
            clipTextEncoder = mockClipTextEncoder,
            clipImageEncoder = mockClipImageEncoder
        )

        // Multiple calls must return the identical object references
        assertSame(mockDenseEncoder, registry.denseEncoder)
        assertSame(mockDenseEncoder, registry.denseEncoder)

        assertSame(mockCrossEncoder, registry.crossEncoder)
        assertSame(mockCrossEncoder, registry.crossEncoder)

        assertSame(mockClipTextEncoder, registry.clipTextEncoder)
        assertSame(mockClipTextEncoder, registry.clipTextEncoder)

        assertSame(mockClipImageEncoder, registry.clipImageEncoder)
        assertSame(mockClipImageEncoder, registry.clipImageEncoder)
    }

    @Test
    fun testConcurrencyInstrumentationTracking() = runBlocking {
        val registry = ModelRegistry(
            context = mockContext,
            denseEncoder = mockDenseEncoder
        )

        assertEquals(0, registry.getActiveInferenceCount())
        assertEquals(0, registry.getPeakInferenceCount())
        assertEquals(0, registry.getTotalInferenceCalls())

        val coroutineCount = 8
        val results = ConcurrentLinkedQueue<Int>()

        val jobs = (1..coroutineCount).map { id ->
            async(Dispatchers.Default) {
                registry.recordInference {
                    val activeDuringExecution = registry.getActiveInferenceCount()
                    results.add(activeDuringExecution)
                    Thread.sleep(20) // simulate inference duration
                    id
                }
            }
        }

        val completed = jobs.awaitAll()
        assertEquals(coroutineCount, completed.size)

        // Verify metrics
        assertEquals(0, registry.getActiveInferenceCount())
        assertEquals(coroutineCount, registry.getTotalInferenceCalls())
        assertTrue("Peak concurrency should be >= 1 under concurrent load", registry.getPeakInferenceCount() >= 1)
    }

    @Test
    fun testCloseReleasesAllInitializedModels() {
        val registry = ModelRegistry(
            context = mockContext,
            denseEncoder = mockDenseEncoder,
            crossEncoder = mockCrossEncoder,
            clipTextEncoder = mockClipTextEncoder,
            clipImageEncoder = mockClipImageEncoder
        )

        registry.close()

        verify(exactly = 1) { mockDenseEncoder.close() }
        verify(exactly = 1) { mockCrossEncoder.close() }
        verify(exactly = 1) { mockClipTextEncoder.close() }
        verify(exactly = 1) { mockClipImageEncoder.close() }
    }
}
