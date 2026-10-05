package com.augt.localseek.eval

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import com.augt.localseek.diagnostics.StallDetector
import com.augt.localseek.search.vector.BinaryRescoreIndex
import com.augt.localseek.search.vector.Int8ExactIndex
import com.augt.localseek.search.vector.LshConfig
import com.augt.localseek.search.vector.LshIndexManager
import com.augt.localseek.search.vector.ScalingStats
import com.github.jelmerk.hnswlib.core.DistanceFunctions
import com.github.jelmerk.hnswlib.core.Item
import com.github.jelmerk.hnswlib.core.ProgressListener
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.Random

/**
 * Part AN: scaling microbenchmark of nearest-neighbour methods on the phone, using PUBLIC vectors only (MiniLM embeddings of BEIR
 * text, made by eval/public_replication/make_scaling_vectors.py and pushed to the app's external files dir `scaling/`), plus one
 * real-data point on the app's own chunk embeddings (read-only; only aggregates are written).
 *
 * Never touches the app's index: the LSH manager gets a ContextWrapper whose filesDir is a temp folder (its saveIndex would
 * otherwise overwrite lsh_index.bin), the database is only read through ChunkDao.getEmbeddingsPage. Run this class alone.
 * Output: scaling_results.json and scaling_results.csv in the same external files dir (aggregates only).
 */
@RunWith(AndroidJUnit4::class)
class ScalingBenchmarkInstrumentedTest {

    companion object {
        private const val TAG = "SCALING"
        private const val DIM = 384
        private const val WARMUP = 50
        private const val PASSES = 3
        private const val K = 10
        private const val HEAP_FRACTION = 0.70
        private const val REAL_HELD_OUT = 200
    }

    // ---------------------------------------------------------------- vector sources

    private interface Vectors {
        val n: Int
        fun read(row: Int, dst: FloatArray)
        /** Heap copy of all vectors when the caller decides it fits; null for the mapped source. */
        fun toHeap(): FloatArray
    }

    private class MappedVectors(file: File, override val n: Int) : Vectors {
        private val raf = RandomAccessFile(file, "r")
        private val buf: FloatBuffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, n.toLong() * DIM * 4)
            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        override fun read(row: Int, dst: FloatArray) { buf.position(row * DIM); buf.get(dst, 0, DIM) }
        override fun toHeap(): FloatArray = FloatArray(n * DIM).also { buf.position(0); buf.get(it, 0, n * DIM) }
        fun close() = raf.close()
    }

    private class ArrayVectors(private val flat: FloatArray) : Vectors {
        override val n: Int get() = flat.size / DIM
        override fun read(row: Int, dst: FloatArray) = System.arraycopy(flat, row * DIM, dst, 0, DIM)
        override fun toHeap(): FloatArray = flat
    }

    // ---------------------------------------------------------------- method groups (one build, several search modes)

    private class Mode(val name: String, val params: String, val search: (FloatArray, Int) -> IntArray)

    private abstract class Group(val family: String) {
        /** Called by long builds so the stall watchdog sees progress. */
        var touch: () -> Unit = {}
        abstract fun analyticBytes(n: Int): Long
        /** Bytes this group holds on the Java heap (defaults to the analytic size); used for the 70% skip rule. */
        open fun heapBytes(n: Int): Long = analyticBytes(n)
        /** Builds the structure; returns a note about storage used (may be empty). */
        abstract fun build(v: Vectors): String
        abstract val modes: List<Mode>
        open fun close() {}
    }

    private class TopKInts(private val k: Int) {
        private val ids = IntArray(k)
        private val sc = FloatArray(k)
        var size = 0
        fun offer(id: Int, score: Float) {
            if (size == k && score <= sc[k - 1]) return
            var i = if (size < k) size++ else k - 1
            while (i > 0 && sc[i - 1] < score) { sc[i] = sc[i - 1]; ids[i] = ids[i - 1]; i-- }
            sc[i] = score; ids[i] = id
        }
        fun result(): IntArray = ids.copyOf(size)
    }

    private class ExactF32(private val heapOk: (Long) -> Boolean) : Group("exact_f32") {
        private var heap: FloatArray? = null
        private var mapped: Vectors? = null
        private var n = 0
        /** Heap bytes only: the float vectors stay in the mapped file (off-heap) when they would not fit comfortably. */
        override fun analyticBytes(n: Int) = n.toLong() * DIM * 4
        override fun heapBytes(n: Int) = if (heapOk(analyticBytes(n))) analyticBytes(n) else 0L
        override fun build(v: Vectors): String {
            n = v.n
            return if (heapOk(analyticBytes(n))) { heap = v.toHeap(); "float32 heap array" } else { mapped = v; "float32 mapped file (off-heap)" }
        }
        private fun scan(q: FloatArray, k: Int): IntArray {
            val top = TopKInts(k)
            val h = heap
            if (h != null) {
                for (row in 0 until n) {
                    var dot = 0f; val b = row * DIM
                    for (i in 0 until DIM) dot += q[i] * h[b + i]
                    top.offer(row, dot)
                }
            } else {
                val m = mapped!!; val tmp = FloatArray(DIM)
                for (row in 0 until n) {
                    m.read(row, tmp)
                    var dot = 0f
                    for (i in 0 until DIM) dot += q[i] * tmp[i]
                    top.offer(row, dot)
                }
            }
            return top.result()
        }
        override val modes = listOf(Mode("exact_f32", "dot over L2-normalised vectors", ::scan))
        override fun close() { heap = null; mapped = null }
    }

    private class Int8Group : Group("exact_int8") {
        private val idx = Int8ExactIndex(DIM)
        override fun analyticBytes(n: Int) = n.toLong() * (DIM + 4 + 8)
        override fun build(v: Vectors): String {
            idx.buildStreaming(LongArray(v.n) { it.toLong() }) { row, dst -> v.read(row, dst) }
            return "int8 codes + scales + ids on the heap"
        }
        override val modes = listOf(Mode("exact_int8", "symmetric per-vector int8") { q, k -> idx.search(q, k).map { it.id.toInt() }.toIntArray() })
    }

    private class BinaryGroup : Group("binary_rescore") {
        private val idx = BinaryRescoreIndex(DIM, 100)
        override fun analyticBytes(n: Int) = n.toLong() * (48 + 8)
        override fun build(v: Vectors): String {
            idx.buildStreaming(LongArray(v.n) { it.toLong() }) { row, dst -> v.read(row, dst) }
            return "sign bits + ids on the heap; float vectors read from the mapped file for rescoring"
        }
        override val modes = listOf(100, 200).map { kp ->
            Mode("binary_rescore_k$kp", "Hamming shortlist k'=$kp then float32 rescoring") { q, k -> idx.search(q, k, kp).map { it.id.toInt() }.toIntArray() }
        }
    }

    private class LshGroup(private val context: Context) : Group("lsh") {
        private var manager: LshIndexManager? = null
        private var tmp: File? = null
        private var cfgNote = ""
        override fun analyticBytes(n: Int) = n.toLong() * (DIM * 4 + 120)
        override fun build(v: Vectors): String {
            val dir = File(context.cacheDir, "lsh_scaling_tmp").also { it.mkdirs() }
            tmp = dir
            // saveIndex writes lsh_index.bin into filesDir: redirect it away from the app's real index
            val ctx = object : ContextWrapper(context) { override fun getFilesDir(): File = dir }
            val m = LshIndexManager(ctx)
            val base = LshConfig.forDatasetSize(v.n)
            // streaming mode needs the Room database; keep the same tables / bits / candidates but score in memory
            val cfg = base.copy(memoryMode = LshConfig.MemoryMode.IN_MEMORY)
            cfgNote = "tables=${cfg.numTables} bits=${cfg.numHashBits} proj=${cfg.projectionDim} cap=${cfg.searchCandidates} probe=${cfg.probeRadius}"
            val list = ArrayList<Pair<Long, FloatArray>>(v.n)
            for (row in 0 until v.n) list.add(row.toLong() to FloatArray(DIM).also { v.read(row, it) })
            touch()
            runBlocking { m.buildIndex(list, cfg) }
            manager = m
            return "LshIndexManager $cfgNote (index file written to a temp folder, not the app's)"
        }
        private fun search(q: FloatArray, k: Int, cap: Int): IntArray =
            runBlocking { manager!!.search(q, k, null, false, cap) }.map { it.id.toInt() }.toIntArray()
        override val modes = listOf(
            Mode("lsh_app", "app LSH configuration, candidate cap = searchCandidates") { q, k -> search(q, k, 0) },
            Mode("lsh_uncapped", "app LSH tables and bits, no candidate cap") { q, k -> search(q, k, -1) }
        )
        override fun close() { manager = null; tmp?.listFiles()?.forEach { it.delete() }; tmp?.delete() }
    }

    private class VecItem(private val rowId: Int, private val vec: FloatArray) : Item<Int, FloatArray> {
        override fun id(): Int = rowId
        override fun vector(): FloatArray = vec
        override fun dimensions(): Int = vec.size
    }

    private class HnswGroup : Group("hnsw") {
        private var index: HnswIndex<Int, FloatArray, VecItem, Float>? = null
        override fun analyticBytes(n: Int) = n.toLong() * (DIM * 4 + 16 + 16 * 2 * 4 + 150)
        override fun build(v: Vectors): String {
            val items = ArrayList<VecItem>(v.n)
            for (row in 0 until v.n) items.add(VecItem(row, FloatArray(DIM).also { v.read(row, it) }))
            val idx: HnswIndex<Int, FloatArray, VecItem, Float> = HnswIndex.newBuilder(DIM, DistanceFunctions.FLOAT_COSINE_DISTANCE, v.n)
                .withM(16).withEfConstruction(200).withEf(16).build()
            val threads = Runtime.getRuntime().availableProcessors()
            idx.addAll(items, threads, ProgressListener { _, _ -> touch() }, 10_000)
            index = idx
            return "hnswlib-core 1.2.1, M=16 efConstruction=200, built with $threads threads"
        }
        override val modes = listOf(16, 32, 64, 128).map { ef ->
            Mode("hnsw_ef$ef", "M=16 efConstruction=200 efSearch=$ef") { q, k ->
                index!!.also { it.ef = ef }.findNearest(q, k).map { it.item().id() }.toIntArray()
            }
        }
        override fun close() { index = null }
    }

    // ---------------------------------------------------------------- the benchmark

    private val rows = JSONArray()
    private val csv = StringBuilder("dataset,n,method,mode,status,build_ms,analytic_bytes,heap_delta_bytes,recall10,p50_us,p95_us,p99_us,mean_us,thermal_start,thermal_end,battery_pct,charging\n")
    private lateinit var outDir: File

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun thermal(ctx: Context): Int = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
    private fun battery(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return (if (scale > 0) level * 100 / scale else -1) to plugged
    }

    private fun flush() {
        for ((name, text) in listOf("scaling_results.csv" to csv.toString(), "scaling_results.json" to rows.toString(1))) {
            File(outDir, name).also { it.writeText(text); it.setReadable(true, false) } // readable for `adb pull` (shell user)
        }
    }

    private fun record(dataset: String, n: Int, family: String, mode: String, params: String, status: String, buildMs: Long, analytic: Long,
                       heapDelta: Long, recall: Double, pass: JSONArray?, med: Int, t0: Int, t1: Int, batt: Int, charging: Boolean, note: String) {
        val o = JSONObject().put("dataset", dataset).put("n", n).put("family", family).put("method", mode).put("params", params)
            .put("status", status).put("buildMs", buildMs).put("analyticBytes", analytic).put("heapDeltaBytes", heapDelta)
            .put("recall10", recall).put("thermalStart", t0).put("thermalEnd", t1).put("batteryPct", batt).put("charging", charging).put("note", note)
        var p50 = 0L; var p95 = 0L; var p99 = 0L; var mean = 0L
        if (pass != null) {
            o.put("passes", pass).put("medianPass", med)
            val m = pass.getJSONObject(med)
            p50 = m.getLong("p50Us"); p95 = m.getLong("p95Us"); p99 = m.getLong("p99Us"); mean = m.getLong("meanUs")
            o.put("p50Us", p50).put("p95Us", p95).put("p99Us", p99).put("meanUs", mean)
        }
        rows.put(o)
        csv.append("$dataset,$n,$family,$mode,$status,$buildMs,$analytic,$heapDelta,${"%.4f".format(recall)},$p50,$p95,$p99,$mean,$t0,$t1,$batt,$charging\n")
        Log.i(TAG, "SCALING row dataset=$dataset n=$n method=$mode status=$status recall10=${"%.3f".format(recall)} p50_us=$p50 p95_us=$p95 build_ms=$buildMs")
        flush()
    }

    private fun runGroup(
        ctx: Context, detector: StallDetector, dataset: String, v: Vectors, queries: FloatArray, nQ: Int, truth: Array<IntArray>, g: Group,
    ) {
        val n = v.n
        val maxHeap = Runtime.getRuntime().maxMemory()
        BenchmarkSafety.requestGc("before ${g.family} $n", detector)
        val used = usedHeap()
        val analytic = g.analyticBytes(n)
        if (ScalingStats.exceedsHeapBudget(used, g.heapBytes(n), maxHeap, HEAP_FRACTION)) {
            val reason = "skipped: used heap $used + analytic $analytic bytes would exceed ${(HEAP_FRACTION * 100).toInt()}% of maxMemory $maxHeap"
            g.modes.forEach { record(dataset, n, g.family, it.name, it.params, "skipped", 0, analytic, 0, 0.0, null, 0, thermal(ctx), thermal(ctx), battery(ctx).first, battery(ctx).second, reason) }
            return
        }
        detector.phase = "build ${g.family} $n"
        BenchmarkSafety.thermalGate(ctx, "build|${g.family}|$n", detector)
        g.touch = { detector.touch() }
        val heapBefore = usedHeap()
        val t0 = System.nanoTime()
        val note = try { g.build(v) } catch (e: OutOfMemoryError) {
            g.close()
            g.modes.forEach { record(dataset, n, g.family, it.name, it.params, "skipped", 0, analytic, 0, 0.0, null, 0, thermal(ctx), thermal(ctx), battery(ctx).first, battery(ctx).second, "OutOfMemoryError during build") }
            return
        }
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        val heapDelta = usedHeap() - heapBefore
        for (mode in g.modes) {
            detector.phase = "search ${mode.name} $n"
            val thermalStart = thermal(ctx)
            val (batt, charging) = battery(ctx)
            // recall on the warm-up-free first pass results; latency over PASSES passes
            val passes = JSONArray(); val p50s = LongArray(PASSES)
            var recallSum = 0.0
            for (q in 0 until minOf(WARMUP, nQ)) mode.search(queryRow(queries, q), K)
            for (pass in 0 until PASSES) {
                BenchmarkSafety.thermalGate(ctx, "search|${mode.name}|$n|$pass", detector)
                val lat = LongArray(nQ)
                for (q in 0 until nQ) {
                    val qv = queryRow(queries, q)
                    val s = System.nanoTime()
                    val res = mode.search(qv, K)
                    lat[q] = (System.nanoTime() - s) / 1000
                    if (pass == 0) recallSum += ScalingStats.recallAtK(res, truth[q], K)
                    if (q % 100 == 0) detector.touch()
                }
                p50s[pass] = ScalingStats.percentile(lat, 50.0)
                passes.put(JSONObject().put("p50Us", p50s[pass]).put("p95Us", ScalingStats.percentile(lat, 95.0))
                    .put("p99Us", ScalingStats.percentile(lat, 99.0)).put("meanUs", lat.average().toLong()))
            }
            record(dataset, n, g.family, mode.name, mode.params, "ok", buildMs, analytic, heapDelta, recallSum / nQ, passes,
                ScalingStats.medianPassIndex(p50s), thermalStart, thermal(ctx), batt, charging, note)
        }
        g.close()
    }

    private fun queryRow(all: FloatArray, q: Int): FloatArray = all.copyOfRange(q * DIM, (q + 1) * DIM)

    private fun groups(ctx: Context, maxHeap: Long): List<Group> = listOf(
        ExactF32 { bytes -> !ScalingStats.exceedsHeapBudget(usedHeap(), bytes, maxHeap, 0.35) },
        Int8Group(), BinaryGroup(), LshGroup(ctx), HnswGroup()
    )

    private fun readFloats(f: File, count: Int): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(count).also { bb.get(it) }
    }

    private fun readInts(f: File, rows: Int, cols: Int): Array<IntArray> {
        val ib = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return Array(rows) { IntArray(cols).also { ib.get(it) } }
    }

    @Test
    fun runScalingBenchmark() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "scaling")
        Log.w(TAG, "SCALING dir=${dir.path} exists=${dir.exists()} isDir=${dir.isDirectory} canRead=${dir.canRead()} files=${dir.list()?.size} header=${File(dir, "scaling_header.json").exists()}")
        assumeTrue("scaling files not pushed to ${dir.path}", File(dir, "scaling_header.json").exists())
        execute(ctx, dir, includeRealData = true)
    }

    /**
     * Harness self-test with small synthetic clustered vectors generated on the device itself (nothing to push, nothing from the
     * app's data). Writes only to the app cache dir. Use `-e class ...ScalingBenchmarkInstrumentedTest#selfTestSmall`.
     */
    @Test
    fun selfTestSmall() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "scaling_selftest").also { it.mkdirs() }
        val sizes = listOf(2_000, 5_000)
        val nQ = 60
        val rnd = Random(7)
        fun unit(): FloatArray { val v = FloatArray(DIM) { rnd.nextGaussian().toFloat() }; val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(DIM) { v[it] / n } }
        val centres = List(30) { unit() }
        fun sample(): FloatArray { val c = centres[rnd.nextInt(centres.size)]; val g = unit(); val v = FloatArray(DIM) { c[it] + g[it] }; val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(DIM) { v[it] / n } }
        val vecs = List(sizes.max()) { sample() }
        val qs = List(nQ) { sample() }
        fun writeF(name: String, rows: List<FloatArray>) {
            val bb = java.nio.ByteBuffer.allocate(rows.size * DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
            rows.forEach { r -> r.forEach { bb.putFloat(it) } }
            File(dir, name).writeBytes(bb.array())
        }
        writeF("scaling_vectors.f32", vecs); writeF("scaling_queries.f32", qs)
        for (n in sizes) {
            val bb = java.nio.ByteBuffer.allocate(nQ * 100 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (q in qs) {
                val top = TopKInts(100)
                for (r in 0 until n) { var d = 0f; for (i in 0 until DIM) d += q[i] * vecs[r][i]; top.offer(r, d) }
                top.result().forEach { bb.putInt(it) }
            }
            File(dir, "scaling_gt_$n.i32").writeBytes(bb.array())
        }
        File(dir, "scaling_header.json").writeText(JSONObject().put("sizes", JSONArray(sizes)).put("queries", nQ).put("ground_truth_top", 100).toString())
        execute(ctx, dir, includeRealData = false)
    }

    private fun execute(ctx: Context, dir: File, includeRealData: Boolean) {
        outDir = dir
        val h = JSONObject(File(dir, "scaling_header.json").readText())
        val sizes = (0 until h.getJSONArray("sizes").length()).map { h.getJSONArray("sizes").getInt(it) }
        val nQ = h.getInt("queries")
        val topGt = h.getInt("ground_truth_top")
        val queries = readFloats(File(dir, "scaling_queries.f32"), nQ * DIM)
        val detector = StallDetector(timeoutMs = 45 * 60_000L)
        val benchEnv = BenchmarkSafety.makeIdle(ctx)
        val watchdog = BenchmarkSafety.startWatchdog(detector, Thread.currentThread())
        val maxHeap = Runtime.getRuntime().maxMemory()
        val meta = JSONObject().put("gitSha", com.augt.localseek.BuildConfig.GIT_SHA).put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("maxHeapBytes", maxHeap).put("cores", Runtime.getRuntime().availableProcessors()).put("benchEnv", benchEnv)
            .put("warmupQueries", WARMUP).put("passes", PASSES).put("k", K).put("header", h)
        try {
            for (n in sizes) {
                val truth = readInts(File(dir, "scaling_gt_$n.i32"), nQ, topGt)
                val v = MappedVectors(File(dir, "scaling_vectors.f32"), n)
                Log.i(TAG, "SCALING start n=$n")
                for (g in groups(ctx, maxHeap)) runGroup(ctx, detector, "beir_public", v, queries, nQ, truth, g)
                v.close()
            }
            if (includeRealData) realDataPoint(ctx, detector, maxHeap)
            File(dir, "scaling_meta.json").also { it.writeText(meta.toString(1)); it.setReadable(true, false) }
            flush()
        } finally {
            watchdog.interrupt()
        }
    }

    /** Real-data point: the app's own chunk embeddings; 200 seeded held-out chunk vectors are the queries (document vectors as queries). */
    private fun realDataPoint(ctx: Context, detector: StallDetector, maxHeap: Long) {
        // raw read-only SQLite (no Room: opening Room could run a migration; the blob format is little-endian float32, VectorConverter)
        val all = ArrayList<FloatArray>()
        val db = SQLiteDatabase.openDatabase(ctx.getDatabasePath("hybrid_search.db").path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT embedding FROM document_chunks WHERE embedding IS NOT NULL ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    val b = c.getBlob(0)
                    if (b.size == DIM * 4) all.add(FloatArray(DIM).also { java.nio.ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) })
                }
            }
        } finally { db.close() }
        if (all.size < REAL_HELD_OUT * 5) { Log.w(TAG, "SCALING real-data point skipped: only ${all.size} vectors"); return }
        val rnd = Random(42)
        val order = (0 until all.size).toMutableList().also { java.util.Collections.shuffle(it, rnd) }
        val heldOut = order.take(REAL_HELD_OUT).toSet()
        val indexed = (0 until all.size).filter { it !in heldOut }
        val flat = FloatArray(indexed.size * DIM)
        indexed.forEachIndexed { r, src -> System.arraycopy(all[src], 0, flat, r * DIM, DIM) }
        val qFlat = FloatArray(REAL_HELD_OUT * DIM)
        heldOut.toList().sorted().forEachIndexed { r, src -> System.arraycopy(all[src], 0, qFlat, r * DIM, DIM) }
        val v = ArrayVectors(flat)
        // exact ground truth by brute force (float32 dot over L2-normalised vectors; ties by lower row)
        val truth = Array(REAL_HELD_OUT) { q ->
            val qv = queryRow(qFlat, q); val top = TopKInts(100); val tmp = FloatArray(DIM)
            for (row in 0 until v.n) { v.read(row, tmp); var d = 0f; for (i in 0 until DIM) d += qv[i] * tmp[i]; top.offer(row, d) }
            top.result()
        }
        Log.i(TAG, "SCALING start real-data n=${v.n}")
        for (g in groups(ctx, maxHeap)) runGroup(ctx, detector, "app_chunks_real", v, qFlat, REAL_HELD_OUT, truth, g)
    }
}
