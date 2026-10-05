package com.augt.localseek.di

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ImageDao
import com.augt.localseek.ml.CrossEncoder
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.ml.ModelRegistry
import com.augt.localseek.ml.clip.ClipImageEncoder
import com.augt.localseek.ml.clip.ClipTextEncoder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppContainerLifecycleTest {

    private lateinit var mockContext: Context
    private lateinit var mockAppContext: Context
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockChunkDao: ChunkDao
    private lateinit var mockImageDao: ImageDao
    private lateinit var mockDenseEncoder: DenseEncoder
    private lateinit var mockCrossEncoder: CrossEncoder
    private lateinit var mockClipTextEncoder: ClipTextEncoder
    private lateinit var mockClipImageEncoder: ClipImageEncoder
    private lateinit var modelRegistry: ModelRegistry
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockAppContext = mockk(relaxed = true)
        every { mockContext.applicationContext } returns mockAppContext
        every { mockAppContext.applicationContext } returns mockAppContext
        every { mockAppContext.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir"), "localseek_test_files")

        mockChunkDao = mockk(relaxed = true)
        mockImageDao = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)
        every { mockDatabase.chunkDao() } returns mockChunkDao
        every { mockDatabase.imageDao() } returns mockImageDao

        mockDenseEncoder = mockk(relaxed = true)
        mockCrossEncoder = mockk(relaxed = true)
        mockClipTextEncoder = mockk(relaxed = true)
        mockClipImageEncoder = mockk(relaxed = true)

        modelRegistry = ModelRegistry(
            context = mockAppContext,
            denseEncoder = mockDenseEncoder,
            crossEncoder = mockCrossEncoder,
            clipTextEncoder = mockClipTextEncoder,
            clipImageEncoder = mockClipImageEncoder
        )

        container = AppContainer(
            context = mockContext,
            database = mockDatabase,
            modelRegistry = modelRegistry
        )
    }

    @Test
    fun testSharedModelInstanceIdentityAcrossComponents() {
        // Assert exactly one DenseEncoder instance shared between registry and denseRetriever
        assertSame("DenseRetriever must share registry DenseEncoder",
            modelRegistry.denseEncoder, container.denseRetriever.encoder)

        // Assert exactly one ClipTextEncoder instance shared between registry and imageRetriever
        assertSame("ImageRetriever must share registry ClipTextEncoder",
            modelRegistry.clipTextEncoder, container.imageRetriever.clipTextEncoder)

        // Assert exactly one CrossEncoder instance shared between registry and crossEncoderReranker
        assertSame("CrossEncoderReranker must share registry CrossEncoder",
            modelRegistry.crossEncoder, container.crossEncoderReranker.crossEncoder)

        // Assert exactly one LshIndexManager instance shared between container and denseRetriever
        assertSame("DenseRetriever must share container LshIndexManager",
            container.lshIndexManager, container.denseRetriever.indexManager)
    }

    @Test
    fun testRetrieverCloseDoesNotCloseSharedModelRegistry() {
        // Calling close on denseRetriever when injected via AppContainer should NOT close shared encoder
        container.denseRetriever.close()
        verify(exactly = 0) { mockDenseEncoder.close() }

        // Calling close on imageRetriever should NOT close shared clipTextEncoder
        container.imageRetriever.close()
        verify(exactly = 0) { mockClipTextEncoder.close() }

        // Calling close on crossEncoderReranker should NOT close shared crossEncoder
        container.crossEncoderReranker.close()
        verify(exactly = 0) { mockCrossEncoder.close() }

        // Only closing the container/registry should close the models
        container.close()
        verify(exactly = 1) { mockDenseEncoder.close() }
        verify(exactly = 1) { mockCrossEncoder.close() }
        verify(exactly = 1) { mockClipTextEncoder.close() }
        verify(exactly = 1) { mockClipImageEncoder.close() }
    }
}
