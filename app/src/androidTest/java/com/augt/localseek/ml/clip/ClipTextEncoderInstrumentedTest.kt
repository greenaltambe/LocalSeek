package com.augt.localseek.ml.clip

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipTextEncoderInstrumentedTest {

    companion object {
        private const val TAG = "ClipTextEncoderTest"
        private const val PAD_ID = 49407L
    }

    private lateinit var tokenizer: ClipBpeTokenizer
    private lateinit var encoder: ClipTextEncoder

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tokenizer = ClipBpeTokenizer(context)
        encoder = ClipTextEncoder(context)
    }

    private fun buildExpectedArray(activeTokens: LongArray): LongArray {
        val result = LongArray(77) { PAD_ID }
        for (i in activeTokens.indices) {
            result[i] = activeTokens[i]
        }
        return result
    }

    @Test
    fun testTokenizerCorrectness() {
        val testCases = listOf(
            TestCase(
                input = "a photo of a dog",
                expectedTokens = buildExpectedArray(longArrayOf(49406, 320, 1125, 539, 320, 1929, 49407))
            ),
            TestCase(
                input = "hello, world!",
                expectedTokens = buildExpectedArray(longArrayOf(49406, 3306, 267, 1002, 256, 49407))
            ),
            TestCase(
                input = "top 10 results in 2026",
                expectedTokens = buildExpectedArray(longArrayOf(49406, 1253, 272, 271, 3790, 530, 273, 271, 273, 277, 49407))
            ),
            TestCase(
                input = "LocalSeek Search Engine",
                expectedTokens = buildExpectedArray(longArrayOf(49406, 9202, 8839, 1920, 5857, 49407))
            ),
            TestCase(
                input = "cat",
                expectedTokens = buildExpectedArray(longArrayOf(49406, 2368, 49407))
            ),
            TestCase(
                input = "the quick brown fox jumps over the lazy dog near the river on a warm sunny afternoon with blue skies and green trees everywhere",
                expectedTokens = buildExpectedArray(longArrayOf(
                    49406, 518, 3712, 2866, 3240, 18911, 962, 518, 10753, 1929, 2252, 518, 2473, 525, 320,
                    3616, 5438, 2716, 593, 1746, 7244, 537, 1901, 4682, 6364, 49407
                ))
            )
        )

        for ((index, tc) in testCases.withIndex()) {
            val output = tokenizer.tokenize(tc.input)
            Log.i(TAG, "Test case $index ('${tc.input}'):")
            Log.i(TAG, "  Expected active count: ${tc.expectedTokens.indexOfFirst { it == PAD_ID && it != tc.expectedTokens[0] }}")
            Log.i(TAG, "  Actual active count:   ${output.inputIds.indexOfFirst { it == PAD_ID && it != output.inputIds[0] }}")

            assertArrayEquals(
                "Tokenizer mismatch for test case #$index ('${tc.input}')",
                tc.expectedTokens,
                output.inputIds
            )
        }
        Log.i(TAG, "[TOKENIZER VERIFICATION PASS] All 6 test cases matched HuggingFace CLIPTokenizer exactly!")
    }

    @Test
    fun testOutputShapeAndSanity() {
        assertTrue("ClipTextEncoder should be available", encoder.isAvailable)
        val embedding = encoder.encode("a photo of a dog")

        assertEquals("Output embedding size must be 512", 512, embedding.size)
        assertFalse("Embedding must not be all-zeros", embedding.all { it == 0f })
        assertFalse("Embedding must not contain NaN", embedding.any { it.isNaN() })
        assertFalse("Embedding must not contain Infinite values", embedding.any { it.isInfinite() })

        Log.i(TAG, "[OUTPUT SANITY PASS] Generated 512-dim normalized embedding successfully. Sample values: [${embedding[0]}, ${embedding[1]}, ${embedding[2]}, ...]")
    }

    @Test
    fun testTextTowerLatencyOnDevice() {
        assertTrue("ClipTextEncoder should be available", encoder.isAvailable)

        val query = "a photo of a dog on a warm sunny afternoon"

        // 1. Warm-up call (discarded)
        val warmupEmbed = encoder.encode(query)
        assertEquals(512, warmupEmbed.size)

        // 2. Timed repetitions
        val repetitions = 25
        val latenciesMs = LongArray(repetitions)

        for (i in 0 until repetitions) {
            val startNs = System.nanoTime()
            val emb = encoder.encode(query)
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
        Log.i(TAG, "CLIP TEXT TOWER ON-DEVICE LATENCY BENCHMARK RESULTS")
        Log.i(TAG, "Repetitions: $repetitions | Query: '$query'")
        Log.i(TAG, "Min Latency:  $minMs ms")
        Log.i(TAG, "Max Latency:  $maxMs ms")
        Log.i(TAG, "Mean Latency: ${"%.2f".format(meanMs)} ms")
        Log.i(TAG, "P95 Latency:  $p95Ms ms")
        Log.i(TAG, "===========================================================")

        val targetTargetMs = 150.0
        val maxBudgetMs = 250.0

        if (meanMs <= targetTargetMs) {
            Log.i(TAG, "[VERIFICATION PASS] Mean latency (${"%.2f".format(meanMs)} ms) is within ideal ≤150ms target!")
        } else if (meanMs <= maxBudgetMs) {
            Log.w(TAG, "[VERIFICATION WARN] Mean latency (${"%.2f".format(meanMs)} ms) passed hard ceiling (250ms) but exceeded 150ms ideal target.")
        } else {
            Log.e(TAG, "[VERIFICATION FAIL] Mean latency (${"%.2f".format(meanMs)} ms) EXCEEDED hard 250ms ceiling!")
        }

        assertTrue(
            "On-device CLIP text tower mean latency (${meanMs}ms) exceeded 250ms hard limit!",
            meanMs <= maxBudgetMs
        )
    }

    private data class TestCase(
        val input: String,
        val expectedTokens: LongArray
    )
}
