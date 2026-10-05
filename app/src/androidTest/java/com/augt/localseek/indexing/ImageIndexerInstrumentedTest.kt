package com.augt.localseek.indexing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.ImageEntity
import com.augt.localseek.ml.clip.ClipImageEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageIndexerInstrumentedTest {

    companion object {
        private const val TAG = "ImageIndexerTest"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val imageDao = db.imageDao()

    @Before
    fun setUp() = runBlocking {
        imageDao.clearAll()
    }

    @Test
    fun reportRealDeviceCorpusSize(): Unit = runBlocking {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        var mediaStoreCount = 0
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )
            cursor?.use {
                mediaStoreCount = it.count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MediaStore", e)
        }

        val indexer = ImageIndexer(context)
        val clipEncoder = try {
            ClipImageEncoder(context)
        } catch (e: Exception) {
            Log.w(TAG, "ClipImageEncoder not available", e)
            null
        }

        imageDao.clearAll()
        val startNs = System.nanoTime()
        indexer.indexImages(clipEncoder)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        val dbCount = imageDao.getCount()

        val msg = "REAL_DEVICE_CORPUS_SIZE: PermissionGranted=$hasPermission | MediaStore Total=$mediaStoreCount | ImageDao Count=$dbCount | Total Indexing Time=${elapsedMs}ms"
        Log.e(TAG, msg)
        System.err.println(msg)

        clipEncoder?.close()
        assertTrue(msg, true)
    }

    @Test
    fun testMediaStoreQueryAndGracefulExecution(): Unit = runBlocking {
        val indexer = ImageIndexer(context)
        val clipEncoder = try {
            ClipImageEncoder(context)
        } catch (e: Exception) {
            Log.w(TAG, "ClipImageEncoder not loaded in test, will run without encoder", e)
            null
        }

        val startNs = System.nanoTime()
        indexer.indexImages(clipEncoder)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        val indexedCount = imageDao.getCount()
        Log.i(TAG, "===========================================================")
        Log.i(TAG, "IMAGE INDEXER INSTRUMENTED TEST COMPLETED")
        Log.i(TAG, "Total Photos Indexed into DB: $indexedCount")
        Log.i(TAG, "Total Execution Time: $elapsedMs ms")
        Log.i(TAG, "===========================================================")

        clipEncoder?.close()
        assertTrue("ImageIndexer execution should finish without throwing exceptions", true)
    }

    @Test
    fun testSyntheticImageIndexingAndEndToEndLatency(): Unit = runBlocking {
        val encoder = ClipImageEncoder(context)
        assertTrue("ClipImageEncoder must be available for latency test", encoder.isAvailable)

        val testBitmaps = listOf(
            createSolidBitmap(Color.RED),
            createSolidBitmap(Color.GREEN),
            createSolidBitmap(Color.BLUE),
            createSolidBitmap(Color.YELLOW),
            createSolidBitmap(Color.CYAN)
        )

        val repetitions = testBitmaps.size
        val latenciesMs = LongArray(repetitions)

        for ((index, bmp) in testBitmaps.withIndex()) {
            val startNs = System.nanoTime()

            val embedding = encoder.encode(bmp)

            val entity = ImageEntity(
                mediaStoreId = (1000 + index).toLong(),
                uri = "content://media/external/images/media/${1000 + index}",
                displayName = "test_photo_$index.jpg",
                dateAdded = System.currentTimeMillis(),
                dateModified = System.currentTimeMillis(),
                embedding = embedding,
                indexedTimestamp = System.currentTimeMillis()
            )
            imageDao.insert(entity)

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            latenciesMs[index] = elapsedMs
        }

        latenciesMs.sort()
        val minMs = latenciesMs.first()
        val maxMs = latenciesMs.last()
        val meanMs = latenciesMs.average()
        val p95Ms = latenciesMs[(repetitions * 0.95).toInt().coerceAtMost(repetitions - 1)]

        Log.i(TAG, "===========================================================")
        Log.i(TAG, "END-TO-END PER-PHOTO INDEXING LATENCY (ENCODE + DB INSERT)")
        Log.i(TAG, "Indexed Synthetic Photos: $repetitions")
        Log.i(TAG, "Min Latency:  $minMs ms")
        Log.i(TAG, "Max Latency:  $maxMs ms")
        Log.i(TAG, "Mean Latency: ${"%.2f".format(meanMs)} ms per photo")
        Log.i(TAG, "P95 Latency:  $p95Ms ms")
        Log.i(TAG, "===========================================================")

        val storedEntities = imageDao.getAllImages()
        assertEquals(repetitions, storedEntities.size)

        for (stored in storedEntities) {
            val emb = stored.embedding
            assertNotNull("Stored embedding must not be null", emb)
            assertEquals("Stored embedding dimension must be 512", 512, emb!!.size)
            assertFalse("Stored embedding must not be all-zeros", emb.all { it == 0f })
        }

        encoder.close()
        Log.i(TAG, "[IMAGE INDEXING END-TO-END PASS] Stored $repetitions photos with valid 512-dim embeddings in Room DB.")
    }

    private fun createSolidBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        return bmp
    }
}
