package com.augt.localseek.ml.clip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class ClipImageEncoderInstrumentedTest {

    companion object {
        private const val TAG = "ClipImageEncoderTest"

        private fun createSolidBitmap(color: Int, width: Int = 224, height: Int = 224): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(color)
            return bmp
        }

        private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "Vector dimensions must match" }
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
            return if (denom > 0f) dot / denom else 0f
        }
    }

    private lateinit var imageEncoder: ClipImageEncoder
    private lateinit var textEncoder: ClipTextEncoder

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        imageEncoder = ClipImageEncoder(context)
        textEncoder = ClipTextEncoder(context)
    }

    @Test
    fun testOutputShapeAndSanity() {
        assertTrue("ClipImageEncoder should be available", imageEncoder.isAvailable)

        val redBmp = createSolidBitmap(Color.RED)
        val greenBmp = createSolidBitmap(Color.GREEN)

        val embRed = imageEncoder.encode(redBmp)
        val embGreen = imageEncoder.encode(greenBmp)

        assertEquals("Output embedding size must be 512", 512, embRed.size)
        assertEquals("Output embedding size must be 512", 512, embGreen.size)

        assertFalse("Red embedding must not be all-zeros", embRed.all { it == 0f })
        assertFalse("Green embedding must not be all-zeros", embGreen.all { it == 0f })

        assertFalse("Red embedding must not contain NaN", embRed.any { it.isNaN() })
        assertFalse("Green embedding must not contain NaN", embGreen.any { it.isNaN() })

        assertFalse("Embeddings for Red and Green images must not be identical", embRed.contentEquals(embGreen))

        val imgSim = cosineSimilarity(embRed, embGreen)
        Log.i(TAG, "[OUTPUT SANITY PASS] Red vs Green image embedding cosine similarity: ${"%.4f".format(imgSim)}")
    }

    @Test
    fun testCrossModalImageTextAlignment() {
        assertTrue("ClipImageEncoder must be available", imageEncoder.isAvailable)
        assertTrue("ClipTextEncoder must be available", textEncoder.isAvailable)

        // 1. Generate test images
        val redBmp = createSolidBitmap(Color.RED)
        val greenBmp = createSolidBitmap(Color.GREEN)
        val blueBmp = createSolidBitmap(Color.BLUE)

        // 2. Encode images
        val embRedImg = imageEncoder.encode(redBmp)
        val embGreenImg = imageEncoder.encode(greenBmp)
        val embBlueImg = imageEncoder.encode(blueBmp)

        // 3. Candidate text descriptions
        val textRed = "a photo of a red background"
        val textGreen = "a photo of a green background"
        val textBlue = "a photo of a blue background"

        val embRedText = textEncoder.encode(textRed)
        val embGreenText = textEncoder.encode(textGreen)
        val embBlueText = textEncoder.encode(textBlue)

        // 4. Compute cross-modal similarities
        val scoreRedImg_RedText = cosineSimilarity(embRedImg, embRedText)
        val scoreRedImg_GreenText = cosineSimilarity(embRedImg, embGreenText)
        val scoreRedImg_BlueText = cosineSimilarity(embRedImg, embBlueText)

        val scoreGreenImg_GreenText = cosineSimilarity(embGreenImg, embGreenText)
        val scoreGreenImg_RedText = cosineSimilarity(embGreenImg, embRedText)

        val scoreBlueImg_BlueText = cosineSimilarity(embBlueImg, embBlueText)
        val scoreBlueImg_RedText = cosineSimilarity(embBlueImg, embRedText)

        Log.i(TAG, "===========================================================")
        Log.i(TAG, "CROSS-MODAL IMAGE <-> TEXT COSINE SIMILARITY MATRIX")
        Log.i(TAG, "--- RED IMAGE ---")
        Log.i(TAG, "  Red Image <-> '$textRed':   ${"%.4f".format(scoreRedImg_RedText)} (MATCH)")
        Log.i(TAG, "  Red Image <-> '$textGreen': ${"%.4f".format(scoreRedImg_GreenText)}")
        Log.i(TAG, "  Red Image <-> '$textBlue':  ${"%.4f".format(scoreRedImg_BlueText)}")

        Log.i(TAG, "--- GREEN IMAGE ---")
        Log.i(TAG, "  Green Image <-> '$textGreen': ${"%.4f".format(scoreGreenImg_GreenText)} (MATCH)")
        Log.i(TAG, "  Green Image <-> '$textRed':   ${"%.4f".format(scoreGreenImg_RedText)}")

        Log.i(TAG, "--- BLUE IMAGE ---")
        Log.i(TAG, "  Blue Image <-> '$textBlue':  ${"%.4f".format(scoreBlueImg_BlueText)} (MATCH)")
        Log.i(TAG, "  Blue Image <-> '$textRed':   ${"%.4f".format(scoreBlueImg_RedText)}")
        Log.i(TAG, "===========================================================")

        // 5. Assert matching text descriptions score meaningfully higher than non-matching ones
        assertTrue(
            "Red image must score higher for red text (${scoreRedImg_RedText}) than green text (${scoreRedImg_GreenText})",
            scoreRedImg_RedText > scoreRedImg_GreenText
        )
        assertTrue(
            "Red image must score higher for red text (${scoreRedImg_RedText}) than blue text (${scoreRedImg_BlueText})",
            scoreRedImg_RedText > scoreRedImg_BlueText
        )
        assertTrue(
            "Green image must score higher for green text (${scoreGreenImg_GreenText}) than red text (${scoreGreenImg_RedText})",
            scoreGreenImg_GreenText > scoreGreenImg_RedText
        )
        assertTrue(
            "Blue image must score higher for blue text (${scoreBlueImg_BlueText}) than red text (${scoreBlueImg_RedText})",
            scoreBlueImg_BlueText > scoreBlueImg_RedText
        )

        Log.i(TAG, "[CROSS-MODAL ALIGNMENT PASS] Text and Image towers share a coherent, aligned 512-dim embedding space!")
    }

    @Test
    fun testImageTowerLatencyOnDevice() {
        assertTrue("ClipImageEncoder should be available", imageEncoder.isAvailable)

        val testBmp = createSolidBitmap(Color.RED)

        // 1. Warm-up call (discarded)
        val warmupEmbed = imageEncoder.encode(testBmp)
        assertEquals(512, warmupEmbed.size)

        // 2. Timed repetitions
        val repetitions = 20
        val latenciesMs = LongArray(repetitions)

        for (i in 0 until repetitions) {
            val startNs = System.nanoTime()
            val emb = imageEncoder.encode(testBmp)
            val elapsedNs = System.nanoTime() - startNs
            assertEquals(512, emb.size)
            latenciesMs[i] = elapsedNs / 1_000_000L
        }

        latenciesMs.sort()
        val minMs = latenciesMs.first()
        val maxMs = latenciesMs.last()
        val meanMs = latenciesMs.average()
        val p95Index = (repetitions * 0.95).toInt().coerceAtMost(repetitions - 1)
        val p95Ms = latenciesMs[p95Index]

        Log.i(TAG, "===========================================================")
        Log.i(TAG, "CLIP IMAGE TOWER ON-DEVICE LATENCY BENCHMARK RESULTS")
        Log.i(TAG, "Repetitions: $repetitions | Input: 224x224 Bitmap")
        Log.i(TAG, "Min Latency:  $minMs ms")
        Log.i(TAG, "Max Latency:  $maxMs ms")
        Log.i(TAG, "Mean Latency: ${"%.2f".format(meanMs)} ms per photo")
        Log.i(TAG, "P95 Latency:  $p95Ms ms")
        Log.i(TAG, "===========================================================")

        // Background indexing budget is relaxed (<2000 ms per image)
        val maxIndexingBudgetMs = 2000.0

        if (meanMs <= 500.0) {
            Log.i(TAG, "[VERIFICATION PASS] Image tower mean latency (${"%.2f".format(meanMs)} ms) is extremely fast for background indexing!")
        } else {
            Log.w(TAG, "[VERIFICATION WARN] Image tower mean latency (${"%.2f".format(meanMs)} ms) is acceptable but will require batched background work.")
        }

        assertTrue(
            "Image tower mean latency (${meanMs}ms) exceeded 2000ms background indexing threshold!",
            meanMs <= maxIndexingBudgetMs
        )
    }
}
