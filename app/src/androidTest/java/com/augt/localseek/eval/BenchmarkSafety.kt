package com.augt.localseek.eval

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.augt.localseek.diagnostics.BenchEnvPolicy
import com.augt.localseek.diagnostics.PoolGuard
import com.augt.localseek.diagnostics.StallDetector
import com.augt.localseek.diagnostics.ThermalGate
import org.json.JSONObject

/**
 * Measurement-safety additions for the benchmark harness (docs/investigations/BENCH_HANG.md). They never touch queries, configs,
 * arm order, scoring or retrieval: they only make sure no background indexing runs during a benchmark and that a stall is diagnosable.
 */
object BenchmarkSafety {
    private const val ENV = "BENCH_ENV"
    private const val STALL = "BENCH_STALL"

    /** Cancels all app work, waits (max 60 s) until none is RUNNING, asserts no foreground service, logs and returns the provenance. */
    fun makeIdle(context: Context): JSONObject {
        val resolver = context.contentResolver
        fun global(name: String): Int? = try { android.provider.Settings.Global.getInt(resolver, name) } catch (_: Throwable) { null }
        val airplane = global("airplane_mode_on")
        BenchEnvPolicy.refusal(airplane)?.let { error(it) }
        BenchEnvPolicy.dirtyRefusal(com.augt.localseek.BuildConfig.GIT_SHA)?.let { error(it) }
        val zen = global("zen_mode")
        val stayOn = global("stay_on_while_plugged_in")
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWork().result.get()
        wm.pruneWork().result.get()
        val deadline = System.currentTimeMillis() + 60_000L
        while (runningWork(wm) > 0) {
            check(System.currentTimeMillis() < deadline) { "BENCH_ENV: indexing work still RUNNING after 60 s; refusing to start the benchmark" }
            Thread.sleep(500)
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val fgServices = am.getRunningServices(200).count { it.service.packageName == context.packageName && it.foreground }
        check(fgServices == 0) { "BENCH_ENV: $fgServices foreground service(s) of the app are active; refusing to start the benchmark" }
        val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        Log.i(ENV, "BENCH_ENV index_idle=true work_cancelled=true foreground_services=0 process_importance=${info.importance} airplane_mode_on=$airplane zen_mode=$zen stay_on=$stayOn")
        return JSONObject().put("indexIdle", true).put("workCancelledBeforeRun", true).put("processImportance", info.importance)
            .put("airplane_mode_on", airplane ?: JSONObject.NULL).put("zen_mode", zen ?: JSONObject.NULL).put("stay_on", stayOn ?: JSONObject.NULL)
            .put("freezerNote", BenchEnvPolicy.FREEZER_NOTE)
    }

    private val gcGuard = com.augt.localseek.diagnostics.GcGuard()

    /** Explicit GC with a 20 s cap on a daemon thread (logs BENCH_GC). A timeout is an environment fault: log BENCH_STALL and fail fast. */
    fun requestGc(label: String, detector: StallDetector) {
        detector.phase = "gc-begin $label"
        val start = System.currentTimeMillis()
        val outcome = gcGuard.request()
        Log.i("BENCH_GC", "label=$label ms=${System.currentTimeMillis() - start} outcome=${outcome.name.lowercase()}")
        check(outcome != com.augt.localseek.diagnostics.GcGuard.Outcome.TIMEOUT) {
            Log.e(STALL, "BENCH_STALL explicit GC did not finish within 20 s (phase=${detector.phase}); numbers after this are not comparable")
            "BENCH_STALL: explicit GC stalled for 20 s at '$label'; failing the run (environment fault)"
        }
    }

    private var thermalGates = 0
    private var thermalGateWaits = 0
    private var thermalWaitMs = 0L
    private var thermalGateTimeouts = 0
    private val timedOutKeys = mutableSetOf<String>()

    /**
     * Waits (max 20 min) until the thermal status is NONE or LIGHT before a run, keeping the stall watchdog fed while waiting.
     * A timeout is not fatal: the run key is remembered and its export row gets thermalGateTimedOut=true.
     */
    fun thermalGate(context: Context, key: String, detector: StallDetector) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        detector.phase = "thermal-gate"
        val r = ThermalGate.await({ pm.currentThermalStatus }, System::currentTimeMillis, { Thread.sleep(it); detector.touch() })
        thermalGates++
        thermalWaitMs += r.waitedMs
        if (r.waitedMs > 0) {
            thermalGateWaits++
            Log.i("BENCH_THERMAL", "BENCH_THERMAL waited_s=${r.waitedMs / 1000} status=${r.status} timedOut=${r.timedOut}")
        }
        if (r.timedOut) { thermalGateTimeouts++; timedOutKeys.add(key) }
    }

    /** Additive per-run field thermalGateTimedOut; the key is backend|queryText|repetitionIndex. */
    fun markThermalGate(runs: org.json.JSONArray?) {
        if (runs == null) return
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            r.put("thermalGateTimedOut", "${r.optString("backend")}|${r.optString("queryText")}|${r.optInt("repetitionIndex")}" in timedOutKeys)
        }
    }

    /** Additive provenance about explicit GCs and the thermal gate. */
    fun withGcStats(env: JSONObject): JSONObject = env.put("gcCalls", gcGuard.calls).put("gcMaxMs", gcGuard.maxMs).put("gcTimeouts", gcGuard.timeouts)
        .put("thermalGates", thermalGates).put("thermalGateWaits", thermalGateWaits).put("thermalGateWaitSeconds", thermalWaitMs / 1000)
        .put("thermalGateTimeouts", thermalGateTimeouts)

    /** Corpus fingerprint at start: table counts and SHA-256 of the main database file (WAL size recorded separately). Records, never enforces. */
    suspend fun corpusFingerprint(context: Context, db: com.augt.localseek.data.AppDatabase): JSONObject {
        val o = JSONObject().put("documents", db.documentDao().getDocumentCount()).put("chunks", db.chunkDao().countAllChunks())
            .put("apps", db.appDao().getCount()).put("images", db.imageDao().getCount())
        try {
            val f = context.getDatabasePath("hybrid_search.db")
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            o.put("dbSha256", md.digest().joinToString("") { "%02x".format(it) }).put("dbBytes", f.length())
            val wal = java.io.File(f.path + "-wal")
            o.put("walBytes", if (wal.exists()) wal.length() else 0L)
        } catch (e: Throwable) {
            o.put("dbSha256", JSONObject.NULL).put("dbSha256Note", "database file not readable: ${e.javaClass.simpleName}")
        }
        return o
    }

    /** Writes a pool file, refusing a header-only one (BENCH_POOL). */
    fun writePool(file: java.io.File, lines: List<String>) {
        PoolGuard.check(file.name, lines)
        file.writeText(lines.joinToString("\n"))
    }

    /**
     * Starts a daemon watchdog. On a 5 minute stall it FIRST logs one plain line (no thread suspension needed), then dumps all stacks
     * (names only) on its own thread with a 10 s join so a blocked getAllStackTraces cannot silence it, then fails the run.
     */
    fun startWatchdog(detector: StallDetector, testThread: Thread): Thread = Thread {
        try {
            while (!detector.isStalled()) Thread.sleep(5_000)
            Log.e(STALL, "BENCH_STALL stalled_for_s=${detector.secondsSinceProgress()} phase=${detector.phase}")
            val dumper = Thread {
                val dump = StallDetector.frames(Thread.getAllStackTraces())
                dump.forEach { Log.e(STALL, it) }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$STALL\n" + dump.joinToString("\n") + "\n") })
            }.apply { isDaemon = true; name = "bench-stall-dump"; start() }
            dumper.join(10_000)
            if (dumper.isAlive) Log.e(STALL, "BENCH_STALL stack dump blocked for 10 s (threads cannot be suspended)")
            testThread.interrupt()
            Thread.sleep(15_000)
        } catch (_: InterruptedException) { return@Thread }
        android.os.Process.killProcess(android.os.Process.myPid()) // blocked uninterruptibly: end the run rather than hang for hours
    }.apply { isDaemon = true; name = "bench-stall-watchdog"; start() }

    private fun runningWork(wm: WorkManager): Int =
        wm.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.RUNNING)).get().size
}
