package com.augt.localseek.eval

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.diagnostics.StallDetector
import com.augt.localseek.search.vector.BinaryRescoreIndex
import com.augt.localseek.search.vector.ScalingStats
import com.github.jelmerk.hnswlib.core.DistanceFunctions
import com.github.jelmerk.hnswlib.core.Item
import com.github.jelmerk.hnswlib.core.ProgressListener
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Release-build latency check of three methods at N = 10k and 50k, on the public scaling vectors that ScalingBenchmarkInstrumentedTest
 * uses (same files, same method: 50 warm-up queries, 3 passes over the 1000 queries, median pass by p50, recall@10 against the
 * pushed ground truth). Subset: exact_f32 (heap array), binary_rescore_k200 (float vectors read from the mapped file) and
 * hnsw_ef64 (M16, efConstruction 200). Writes release_subset_results.json next to the scaling files; never touches the app's index
 * or data. Run:
 *
 *   adb shell am instrument -w -e class com.augt.localseek.eval.ScalingReleaseSubsetInstrumentedTest \
 *     com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ScalingReleaseSubsetInstrumentedTest {

    private companion object {
        const val TAG = "SCALING_REL"
        const val DIM = 384
        const val WARMUP = 50
        const val PASSES = 3
        const val K = 10
        val SIZES = listOf(10_000, 50_000)
    }

    private class VecItem(private val rowId: Int, private val vec: FloatArray) : Item<Int, FloatArray> {
        override fun id(): Int = rowId
        override fun vector(): FloatArray = vec
        override fun dimensions(): Int = vec.size
    }

    private fun readFloats(f: File, count: Int): FloatArray {
        val fb = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(count).also { fb.get(it) }
    }

    private fun readInts(f: File, rows: Int, cols: Int): Array<IntArray> {
        val ib = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return Array(rows) { IntArray(cols).also { ib.get(it) } }
    }

    private fun insertTop(ids: IntArray, sc: FloatArray, size: Int, k: Int, id: Int, score: Float): Int {
        var n = size
        if (n == k && score <= sc[k - 1]) return n   // full and not better than the current k-th: same rule as ScalingBenchmarkInstrumentedTest.TopKInts
        var i = if (n < k) n++ else k - 1
        while (i > 0 && sc[i - 1] < score) { sc[i] = sc[i - 1]; ids[i] = ids[i - 1]; i-- }
        sc[i] = score; ids[i] = id
        return n
    }

    @Test
    fun releaseSubset() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "scaling")
        assumeTrue("scaling files not found in ${dir.path}", File(dir, "scaling_header.json").exists())
        val header = JSONObject(File(dir, "scaling_header.json").readText())
        val nQ = header.getInt("queries")
        val topGt = header.getInt("ground_truth_top")
        val queries = readFloats(File(dir, "scaling_queries.f32"), nQ * DIM)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val detector = StallDetector(timeoutMs = 30 * 60_000L)
        val out = JSONArray()
        val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Log.i(TAG, "RELSUB start debuggable=$debuggable sdk=${android.os.Build.VERSION.SDK_INT} maxHeap=${Runtime.getRuntime().maxMemory()}")

        for (n in SIZES) {
            val truth = readInts(File(dir, "scaling_gt_$n.i32"), nQ, topGt)
            val raf = RandomAccessFile(File(dir, "scaling_vectors.f32"), "r")
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, n.toLong() * DIM * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            fun readRow(row: Int, dst: FloatArray) { mapped.position(row * DIM); mapped.get(dst, 0, DIM) }
            val heap = FloatArray(n * DIM).also { mapped.position(0); mapped.get(it, 0, n * DIM) }

            val binary = BinaryRescoreIndex(DIM, 200).also { it.buildStreaming(LongArray(n) { r -> r.toLong() }) { r, d -> readRow(r, d) } }
            val items = ArrayList<VecItem>(n).also { l -> for (r in 0 until n) l.add(VecItem(r, FloatArray(DIM).also { readRow(r, it) })) }
            val hnsw: HnswIndex<Int, FloatArray, VecItem, Float> = HnswIndex.newBuilder(DIM, DistanceFunctions.FLOAT_COSINE_DISTANCE, n)
                .withM(16).withEfConstruction(200).withEf(64).build()
            hnsw.addAll(items, Runtime.getRuntime().availableProcessors(), ProgressListener { _, _ -> detector.touch() }, 10_000)

            val methods = listOf<Pair<String, (FloatArray) -> IntArray>>(
                "exact_f32" to { q ->
                    val ids = IntArray(K); val sc = FloatArray(K); var size = 0
                    for (row in 0 until n) {
                        var dot = 0f; val b = row * DIM
                        for (i in 0 until DIM) dot += q[i] * heap[b + i]
                        size = insertTop(ids, sc, size, K, row, dot)
                    }
                    ids.copyOf(size)
                },
                "binary_rescore_k200" to { q -> binary.search(q, K, 200).map { it.id.toInt() }.toIntArray() },
                "hnsw_ef64" to { q -> hnsw.findNearest(q, K).map { it.item().id() }.toIntArray() },
            )
            for ((name, search) in methods) {
                System.gc()
                val thermalStart = pm.currentThermalStatus
                for (q in 0 until WARMUP) search(queries.copyOfRange(q * DIM, (q + 1) * DIM))
                var recallSum = 0.0
                val p50s = LongArray(PASSES)
                val passes = JSONArray()
                for (pass in 0 until PASSES) {
                    BenchmarkSafety.thermalGate(ctx, "relsub|$name|$n|$pass", detector)
                    val lat = LongArray(nQ)
                    for (q in 0 until nQ) {
                        val qv = queries.copyOfRange(q * DIM, (q + 1) * DIM)
                        val s = System.nanoTime()
                        val res = search(qv)
                        lat[q] = (System.nanoTime() - s) / 1000
                        if (pass == 0) recallSum += ScalingStats.recallAtK(res, truth[q], K)
                        if (q % 100 == 0) detector.touch()
                    }
                    p50s[pass] = ScalingStats.percentile(lat, 50.0)
                    passes.put(JSONObject().put("p50Us", p50s[pass]).put("p95Us", ScalingStats.percentile(lat, 95.0)).put("p99Us", ScalingStats.percentile(lat, 99.0)))
                }
                val m = passes.getJSONObject(ScalingStats.medianPassIndex(p50s))
                val row = JSONObject().put("n", n).put("method", name).put("recall10", recallSum / nQ).put("p50Us", m.getLong("p50Us"))
                    .put("p95Us", m.getLong("p95Us")).put("p99Us", m.getLong("p99Us")).put("passes", passes)
                    .put("thermalStart", thermalStart).put("thermalEnd", pm.currentThermalStatus).put("debuggable", debuggable)
                out.put(row)
                Log.i(TAG, "RELSUB n=$n method=$name recall10=${"%.3f".format(recallSum / nQ)} p50_us=${m.getLong("p50Us")} p95_us=${m.getLong("p95Us")}")
            }
            raf.close()
        }
        File(dir, "release_subset_results.json").also { it.writeText(out.toString(1)); it.setReadable(true, false) }
        Log.i(TAG, "RELSUB done")
    }
}
