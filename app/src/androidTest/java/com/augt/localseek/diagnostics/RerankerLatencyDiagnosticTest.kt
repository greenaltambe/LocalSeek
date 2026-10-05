package com.augt.localseek.diagnostics

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.ml.BertTokenizer
import com.augt.localseek.ml.CrossEncoder
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.ml.clip.ClipBpeTokenizer
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.CrossEncoderReranker
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Diagnostic only (no production code is changed): attributes the ~320 ms/pair cross-encoder cost seen in the
 * paper-v1.1 benchmark. Uses synthetic texts only. All results are logged with tag [TAG] as "RESULT ..." lines.
 *
 *  1. raw interpreter loop, 20 query/doc pairs, NNAPI on/off x threads {1,2,4,6}
 *  2. real CrossEncoderReranker.rerank path, 20 candidates x 5 queries x 5 repetitions
 *  3. per-component overhead probes (tokenisation, per-candidate Log.v)
 *  4. CLIP text / image encoders, NNAPI on/off (informational)
 *
 * Run:  adb shell am instrument -w -e class com.augt.localseek.diagnostics.RerankerLatencyDiagnosticTest \
 *         com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RerankerLatencyDiagnosticTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        private const val TAG = "RerankDiag"
        private const val MODEL_FILE = "models/cross_encoder.tflite"
        private const val MAX_LENGTH = 256 // same as CrossEncoder.MAX_LENGTH
        private const val N_PAIRS = 20
        private const val WARMUP_ROUNDS = 3
        private const val TIMED_ROUNDS = 5

        private val QUERIES = listOf(
            "quarterly budget report", "how to reset router password", "machine learning lecture notes",
            "flight booking confirmation", "semester timetable"
        )
        private val VOCAB = ("the of and to in is for that with on as by this are from at or an be have it not data model " +
            "system report project meeting budget analysis network password router lecture notes learning machine " +
            "flight booking schedule semester student course exam result figure table section review method").split(" ")
    }

    // ---- synthetic data -------------------------------------------------------------------------------------

    /** Deterministic pseudo-text of [words] words; lengths 20..220 words emulate chunk snippets. */
    private fun doc(seed: Int, words: Int): String {
        var x = seed * 2654435761L + 12345
        return (0 until words).joinToString(" ") {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            VOCAB[((x ushr 33) % VOCAB.size).toInt()]
        }
    }

    private val docs = List(N_PAIRS) { doc(it + 1, 20 + (it * 10)) }

    private fun loadModel(name: String): MappedByteBuffer =
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }

    private class Stats(val label: String, val samplesMs: DoubleArray) {
        val mean get() = samplesMs.average()
        val min get() = samplesMs.min()
        val max get() = samplesMs.max()
        fun line() = "%s mean=%.1f min=%.1f max=%.1f n=%d".format(label, mean, min, max, samplesMs.size)
    }

    private fun result(msg: String) = Log.i(TAG, "RESULT $msg")

    // ---- 1. raw interpreter loop ------------------------------------------------------------------------------

    private class PairInputs(val inputs: Array<Any?>)

    /** Builds inputs exactly like CrossEncoder.score (same index detection, same int32/int64 handling). */
    private fun buildInputs(interp: Interpreter, ids: IntArray, mask: IntArray, types: IntArray): PairInputs {
        var idsIdx = -1; var maskIdx = -1; var typeIdx = -1; var int64 = false
        val n = interp.inputTensorCount
        for (i in 0 until n) {
            val t = interp.getInputTensor(i)
            val name = t.name().lowercase()
            if (t.dataType() == DataType.INT64) int64 = true
            if (name.contains("token_type") || name.contains("segment") || name.contains("type")) typeIdx = i
            else if (name.contains("mask")) maskIdx = i
            else if (name.contains("input_ids") || name.contains("ids")) idsIdx = i
        }
        if (idsIdx == -1) idsIdx = 0
        if (maskIdx == -1) maskIdx = if (n > 1) 1 else -1
        if (typeIdx == -1 && n > 2) typeIdx = 2
        val inputs = arrayOfNulls<Any>(n)
        if (int64) {
            if (idsIdx in 0 until n) inputs[idsIdx] = arrayOf(LongArray(MAX_LENGTH) { ids[it].toLong() })
            if (maskIdx in 0 until n) inputs[maskIdx] = arrayOf(LongArray(MAX_LENGTH) { mask[it].toLong() })
            if (typeIdx in 0 until n) inputs[typeIdx] = arrayOf(LongArray(MAX_LENGTH) { types[it].toLong() })
        } else {
            if (idsIdx in 0 until n) inputs[idsIdx] = arrayOf(ids)
            if (maskIdx in 0 until n) inputs[maskIdx] = arrayOf(mask)
            if (typeIdx in 0 until n) inputs[typeIdx] = arrayOf(types)
        }
        return PairInputs(inputs)
    }

    private fun describeModel(interp: Interpreter) {
        for (i in 0 until interp.inputTensorCount) {
            val t = interp.getInputTensor(i)
            result("cross_encoder input$i name=${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()}")
        }
        for (i in 0 until interp.outputTensorCount) {
            val t = interp.getOutputTensor(i)
            result("cross_encoder output$i name=${t.name()} shape=${t.shape().contentToString()} type=${t.dataType()}")
        }
    }

    private fun rawLoop(nnapi: Boolean, threads: Int, tokenizer: BertTokenizer) {
        val tag = "raw nnapi=$nnapi threads=$threads"
        val initStart = System.nanoTime()
        val interp = try {
            Interpreter(loadModel(MODEL_FILE), Interpreter.Options().apply { setUseNNAPI(nnapi); setNumThreads(threads) })
        } catch (e: Throwable) {
            result("$tag FAILED to create interpreter: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val initMs = (System.nanoTime() - initStart) / 1e6
        try {
            if (nnapi && threads == 1) describeModel(interp)
            val pairs = docs.map { d ->
                val (ids, mask, types) = tokenizer.tokenizePair(QUERIES[0], d, MAX_LENGTH)
                buildInputs(interp, ids, mask, types)
            }
            val out = FloatArray(1)
            fun round(): DoubleArray = DoubleArray(pairs.size) { i ->
                val t0 = System.nanoTime()
                interp.runForMultipleInputsOutputs(pairs[i].inputs, mapOf(0 to out))
                (System.nanoTime() - t0) / 1e6
            }
            val warm = (0 until WARMUP_ROUNDS).map { round() }
            val warmMean = warm.last().average()
            // Guard: a pathologically slow combination (e.g. NNAPI reference CPU) gets fewer timed rounds.
            val rounds = if (warmMean > 800) 2 else TIMED_ROUNDS
            val samples = (0 until rounds).flatMap { round().toList() }.toDoubleArray()
            result("$tag init_ms=%.0f warm_round_mean_ms=%.1f %s (ms per pair, %d rounds)".format(initMs, warmMean, Stats("timed", samples).line(), rounds))
        } catch (e: Throwable) {
            result("$tag FAILED while running: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            interp.close()
        }
    }

    @Test
    fun diagnoseRerankerLatency() {
        val tokenizer = BertTokenizer(context)

        // ---- 3a. tokenisation cost for 20 pairs ----
        repeat(3) { docs.forEach { tokenizer.tokenizePair(QUERIES[0], it, MAX_LENGTH) } }
        val tokMs = (0 until 10).map {
            val t0 = System.nanoTime()
            docs.forEach { d -> tokenizer.tokenizePair(QUERIES[it % QUERIES.size], d, MAX_LENGTH) }
            (System.nanoTime() - t0) / 1e6 / docs.size
        }.toDoubleArray()
        result(Stats("tokenizePair ms per pair", tokMs).line())

        // ---- 3b. per-candidate Log.v cost (same shape/size as CrossEncoderReranker.kt:94) ----
        val msg = "rerank id=1234 stableKey=FILE:0123456789abcdef0123456789abcdef01234567 type=FILE initial=0.0123 normFused=0.5 crossLogit=-3.21 calibrated=0.039 final=0.31"
        val logMs = (0 until 10).map {
            val t0 = System.nanoTime()
            repeat(N_PAIRS) { Log.v("CrossEncoderReranker", msg) }
            (System.nanoTime() - t0) / 1e6 / N_PAIRS
        }.toDoubleArray()
        result(Stats("Log.v ms per call", logMs).line())

        // ---- 1. raw loops ----
        for (nnapi in listOf(false, true)) for (threads in listOf(1, 2, 4, 6)) rawLoop(nnapi, threads, tokenizer)

        // ---- 2. real CrossEncoderReranker path (CrossEncoder uses NNAPI=false, threads=4) ----
        val encoder = CrossEncoder(context)
        val reranker = CrossEncoderReranker(context, encoder, ownsResources = true)
        val candidates = docs.mapIndexed { i, d ->
            SearchResult(
                id = i.toLong() + 1, title = "doc$i", snippet = d, filePath = "/synthetic/$i", fileType = "txt",
                score = 1f - i * 0.01f, modifiedAt = 0L, entityType = EntityType.FILE, stableKey = "SYN:$i"
            )
        }
        val real = ArrayList<Double>()
        var coldMs = -1.0
        for ((qi, q) in QUERIES.withIndex()) {
            for (rep in 0 until 5) {
                reranker.clearCache() // no score-cache hits, as in BenchmarkRunner.kt:330
                val t0 = System.nanoTime()
                runBlocking { reranker.rerank(q, candidates, topK = N_PAIRS, returnTopK = N_PAIRS, maxRerankTimeMs = 120_000L) }
                val ms = (System.nanoTime() - t0) / 1e6
                if (qi == 0 && rep == 0) coldMs = ms else real.add(ms)
                result("reranker.rerank q=$qi rep=$rep total_ms=%.0f per_pair_ms=%.1f".format(ms, ms / N_PAIRS))
            }
        }
        val perPair = real.map { it / N_PAIRS }.toDoubleArray()
        result("reranker.rerank first (cold) call total_ms=%.0f".format(coldMs))
        result(Stats("reranker.rerank ms per pair (24 warm calls)", perPair).line())
        reranker.close()

        // ---- 4. CLIP encoders (informational) ----
        for (nnapi in listOf(false, true)) clipText(nnapi)
        for (nnapi in listOf(false, true)) clipImage(nnapi)
    }

    private fun clipText(nnapi: Boolean) {
        val tag = "clip_text nnapi=$nnapi threads=4"
        try {
            val tok = ClipBpeTokenizer(context)
            val interp = Interpreter(
                ClipAssetPackManager.loadModelFile(context, "models/clip/clip_text_encoder_fp16.tflite"),
                Interpreter.Options().apply { setUseNNAPI(nnapi); setNumThreads(4) }
            )
            try {
                val o = tok.tokenize("a photo of a beach at sunset")
                val out = Array(1) { FloatArray(512) }
                fun once(): Double {
                    val t0 = System.nanoTime()
                    interp.runForMultipleInputsOutputs(arrayOf(arrayOf(o.inputIds), arrayOf(o.attentionMask)), mapOf(1 to out))
                    return (System.nanoTime() - t0) / 1e6
                }
                repeat(3) { once() }
                result("$tag " + Stats("ms per encode", DoubleArray(10) { once() }).line())
            } finally { interp.close() }
        } catch (e: Throwable) {
            result("$tag UNAVAILABLE/FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun clipImage(nnapi: Boolean) {
        val tag = "clip_image nnapi=$nnapi threads=4"
        try {
            val interp = Interpreter(
                ClipAssetPackManager.loadModelFile(context, "models/clip/clip_image_encoder_fp16.tflite"),
                Interpreter.Options().apply { setUseNNAPI(nnapi); setNumThreads(4) }
            )
            try {
                val input = Array(1) { y -> Array(224) { r -> Array(224) { c -> FloatArray(3) { ch -> ((y + r + c + ch) % 17) / 17f - 0.5f } } } }
                val out = Array(1) { FloatArray(512) }
                fun once(): Double {
                    val t0 = System.nanoTime()
                    interp.runForMultipleInputsOutputs(arrayOf(input), mapOf(1 to out))
                    return (System.nanoTime() - t0) / 1e6
                }
                repeat(3) { once() }
                result("$tag " + Stats("ms per encode", DoubleArray(10) { once() }).line())
            } finally { interp.close() }
        } catch (e: Throwable) {
            result("$tag UNAVAILABLE/FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
