package com.augt.localseek.retrieval

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.ml.clip.ClipImageEncoder
import com.augt.localseek.model.EntityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageRetrievalEndToEndInstrumentedTest {

    companion object {
        private const val TAG = "ImageRetrievalTest"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val imageDao = db.imageDao()

    @Before
    fun setUp() = runBlocking {
        imageDao.clearAll()
    }

    @Test
    fun testEndToEndImageSearchRetrievalAndLatency(): Unit = runBlocking {
        val imageEncoder = ClipImageEncoder(context)
        assertTrue("ClipImageEncoder must be available for test", imageEncoder.isAvailable)

        val redBmp = createSolidBitmap(Color.RED)
        val greenBmp = createSolidBitmap(Color.GREEN)
        val blueBmp = createSolidBitmap(Color.BLUE)

        val redEmb = imageEncoder.encode(redBmp)
        val greenEmb = imageEncoder.encode(greenBmp)
        val blueEmb = imageEncoder.encode(blueBmp)

        imageDao.insertAll(
            listOf(
                ImageEntity(mediaStoreId = 101L, uri = "content://media/external/images/media/101", displayName = "red_photo.jpg", dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(), embedding = redEmb, indexedTimestamp = System.currentTimeMillis()),
                ImageEntity(mediaStoreId = 102L, uri = "content://media/external/images/media/102", displayName = "green_photo.jpg", dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(), embedding = greenEmb, indexedTimestamp = System.currentTimeMillis()),
                ImageEntity(mediaStoreId = 103L, uri = "content://media/external/images/media/103", displayName = "blue_photo.jpg", dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(), embedding = blueEmb, indexedTimestamp = System.currentTimeMillis())
            )
        )

        val imageRetriever = ImageRetriever(context)
        assertTrue("ImageRetriever must be available for test", imageRetriever.isAvailable)

        val query = "a photo of a red background"
        val startNs = System.nanoTime()
        val results = imageRetriever.search(query, topK = 10, threshold = 0.20f)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        Log.i(TAG, "===========================================================")
        Log.i(TAG, "END-TO-END IMAGE RETRIEVAL BENCHMARK")
        Log.i(TAG, "Query: \"$query\"")
        Log.i(TAG, "Results Returned: ${results.size}")
        Log.i(TAG, "Total Image Search Latency: $elapsedMs ms")
        Log.i(TAG, "===========================================================")

        assertFalse("Search results must not be empty", results.isEmpty())
        val topMatch = results.first()

        assertEquals("Top match entityType must be IMAGE", EntityType.IMAGE, topMatch.entityType)
        assertEquals("Top match title must be red_photo.jpg", "red_photo.jpg", topMatch.title)
        assertEquals("Top match URI must match expected URI", "content://media/external/images/media/101", topMatch.filePath)
        assertTrue("Top match score must be >= 0.25", topMatch.score >= 0.25f)

        imageEncoder.close()
        imageRetriever.close()

        Log.i(TAG, "[IMAGE RETRIEVAL END-TO-END PASS] Latency: ${elapsedMs}ms | Top Match: ${topMatch.title} (score=${"%.4f".format(topMatch.score)})")
    }

    @Test
    fun testBenchmarkLoggingCapturesImageResults(): Unit = runBlocking {
        val imageEncoder = ClipImageEncoder(context)
        val redBmp = createSolidBitmap(Color.RED)
        val redEmb = imageEncoder.encode(redBmp)

        imageDao.insert(
            ImageEntity(mediaStoreId = 201L, uri = "content://media/external/images/media/201", displayName = "red_photo_benchmark.jpg", dateAdded = System.currentTimeMillis(), dateModified = System.currentTimeMillis(), embedding = redEmb, indexedTimestamp = System.currentTimeMillis())
        )

        val benchmarkDao = db.benchmarkRunDao()
        benchmarkDao.clearAll()

        val record = BenchmarkRunEntity(
            runSessionId = "test_session",
            queryId = "q_red",
            queryText = "red photo",
            timestamp = System.currentTimeMillis(),
            deviceModel = "TestDevice",
            androidVersion = "14",
            backend = "hybrid_threshold",
            corpusSizeChunks = 100,
            corpusSizeApps = 10,
            corpusSizeContacts = 10,
            latencyBm25Ms = 10,
            latencyDenseMs = 20,
            latencyFusionMs = 5,
            latencyRerankMs = 5,
            latencyTotalMs = 40,
            memoryMbPeak = 50.0f,
            batteryPctBefore = 100,
            batteryPctAfter = 100,
            resultIdsJson = "[\"IMAGE:201\"]",
            resultScoresJson = "[0.324]",
            resultEntityTypesJson = "[\"IMAGE\"]",
            resultTitlesJson = "[\"red_photo_benchmark.jpg\"]",
            resultSnippetsJson = "[\"Photo: red_photo_benchmark.jpg\"]"
        )
        benchmarkDao.insert(record)

        val runs = benchmarkDao.getAll()
        assertFalse("Benchmark runs must not be empty", runs.isEmpty())
        val loggedRun = runs.first()

        assertTrue("Logged resultIdsJson must contain IMAGE: prefix", loggedRun.resultIdsJson.contains("IMAGE:201"))
        assertTrue("Logged resultEntityTypesJson must contain IMAGE", loggedRun.resultEntityTypesJson.contains("IMAGE"))
        assertTrue("Logged resultScoresJson must contain non-zero score", loggedRun.resultScoresJson.contains("0.324"))

        // Test QrelsPoolBuilder with this run
        val pool = QrelsPoolBuilder.buildPool(runs)
        assertFalse("Pooled candidates must not be empty", pool.isEmpty())
        val pooledImage = pool.first { it.resultId == "IMAGE:201" }

        assertEquals("IMAGE:201", pooledImage.resultId)
        assertEquals("IMAGE", pooledImage.entityType)
        assertEquals("red_photo_benchmark.jpg", pooledImage.title)

        imageEncoder.close()
        Log.i(TAG, "[BENCHMARK LOGGING VERIFIED] IMAGE candidates successfully logged and pooled with 'IMAGE:' resultId format.")
    }

    private fun createSolidBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        return bmp
    }
}
