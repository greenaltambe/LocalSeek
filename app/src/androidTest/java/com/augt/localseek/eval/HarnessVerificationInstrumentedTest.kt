package com.augt.localseek.eval

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.di.AppContainer
import com.augt.localseek.logging.BenchmarkLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HarnessVerificationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var database: AppDatabase
    private lateinit var benchmarkRunner: BenchmarkRunner

    companion object {
        private const val TAG = "HarnessVerification"

        val TEST_QUERIES = listOf(
            "aadhar",
            "anime",
            "driver",
            "marksheet",
            "whatsapp"
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        database = container.database
        benchmarkRunner = container.benchmarkRunner
    }

    private fun getBatteryPct(): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun executeHarnessVerification() {
        runBlocking {
            Log.i(TAG, "=== STARTING PHASE 8 HARNESS VERIFICATION ===")

        // 1. Verify corpus presence
        val chunkCount = database.chunkDao().countAllChunks()
        val appCount = database.appDao().getCount()
        val contactCount = database.contactDao().getCount()
        val imageCount = database.imageDao().getCount()
        Log.i(TAG, "Corpus verification: chunks=$chunkCount, apps=$appCount, contacts=$contactCount, images=$imageCount")
        assertTrue("Corpus chunks must be populated", chunkCount > 0)

        // 2. Initialize LSH index
        container.denseRetriever.initializeIndex()
        val lshVectors = container.lshIndexManager.indexedVectorCount
        Log.i(TAG, "LSH index initialized with vectors=$lshVectors")
        assertTrue("LSH index vectors must be populated", lshVectors > 0)

        // 3. Warm-up pass (ensures TFLite runtime, SQLite, and JIT are initialized)
        Log.i(TAG, "Executing warm-up query...")
        container.searchEngine.search("warmup query", RetrievalConfig.CLEAN)
        Log.i(TAG, "Warm-up query completed. Battery: ${getBatteryPct()}%")

        // 4. Test Suite: Small Harness Suite covering all required arms
        val dao = database.benchmarkRunDao()
        dao.clearAll()
        assertEquals(0, dao.getCount())

        val testConfigs = listOf(
            RetrievalConfig.BM25,
            RetrievalConfig.DENSE_LSH,
            RetrievalConfig.CLEAN,
            RetrievalConfig.LEGACY,
            RetrievalConfig.HYBRID_GLOBAL,
            RetrievalConfig.HYBRID_PER_TYPE
        )

        val suiteSessionId = "harness-suite-${System.currentTimeMillis()}"
        Log.i(TAG, "Running Harness Verification Suite: ${TEST_QUERIES.size} queries across ${testConfigs.size} configs (Session: $suiteSessionId)")

        val allRecords = mutableListOf<BenchmarkRunEntity>()
        var timeoutOccurred = false

        for (query in TEST_QUERIES) {
            val runs = benchmarkRunner.runBenchmarkSuite(
                query = query,
                runSessionId = suiteSessionId,
                configs = testConfigs
            )
            Log.i(
                TAG,
                "Query '$query' produced ${runs.size}/${testConfigs.size} records: " +
                runs.joinToString(", ") { "${it.backend}(rerank=${it.latencyRerankMs}ms, total=${it.latencyTotalMs}ms)" }
            )
            allRecords.addAll(runs)
        }

        Log.i(TAG, "Harness Verification Suite produced ${allRecords.size} total records.")
        assertTrue("Expected records to be produced", allRecords.isNotEmpty())

        // 5. Verification: Legacy Rerank Latency & No Whitelist Masking
        val legacyRecords = allRecords.filter { it.backend == "legacy" }
        assertTrue("Legacy records must be produced (got ${legacyRecords.size})", legacyRecords.isNotEmpty())

        for (rec in legacyRecords) {
            val rerankMs = rec.latencyRerankMs ?: 0L
            Log.i(TAG, "LEGACY check: query='${rec.queryText}', rerankLatencyMs=$rerankMs, totalLatencyMs=${rec.latencyTotalMs}")
            assertTrue(
                "Legacy rerank latency must be greater than 0ms when neural reranking ran (got $rerankMs ms)",
                rerankMs > 0L
            )
            assertTrue(
                "Legacy total latency must be >= rerank latency",
                rec.latencyTotalMs >= rerankMs
            )
        }

        // 6. Verification: Disabled rerank arms must have latencyRerankMs == 0
        val bm25Records = allRecords.filter { it.backend == "bm25" }
        for (rec in bm25Records) {
            val rerankMs = rec.latencyRerankMs ?: 0L
            assertEquals("BM25 must report 0ms rerank latency", 0L, rerankMs)
        }

        // 7. Verification: Enabled rerank arms (CLEAN, HYBRID_GLOBAL, HYBRID_PER_TYPE)
        val neuralArms = listOf("clean", "hybrid_global", "hybrid_per_type")
        for (arm in neuralArms) {
            val armRecords = allRecords.filter { it.backend == arm }
            for (rec in armRecords) {
                val rerankMs = rec.latencyRerankMs ?: 0L
                assertTrue(
                    "$arm must report rerank latency > 0ms (got $rerankMs ms)",
                    rerankMs > 0L
                )
                assertTrue(
                    "$arm total latency must be >= rerank latency",
                    rec.latencyTotalMs >= rerankMs
                )
            }
        }

        // 8. Verification: Cache Isolation (Pass A -> Pass B -> Pass A)
        Log.i(TAG, "=== STARTING CACHE ISOLATION TEST (A -> B -> A) ===")
        val isolationQuery = "marksheet"
        val isolationSession = "cache-isolation-${System.currentTimeMillis()}"

        val passA1 = benchmarkRunner.runBenchmarkSuite(
            query = isolationQuery,
            runSessionId = isolationSession,
            configs = listOf(RetrievalConfig.HYBRID_GLOBAL)
        ).first()

        val passB = benchmarkRunner.runBenchmarkSuite(
            query = isolationQuery,
            runSessionId = isolationSession,
            configs = listOf(RetrievalConfig.HYBRID_PER_TYPE)
        ).first()

        val passA2 = benchmarkRunner.runBenchmarkSuite(
            query = isolationQuery,
            runSessionId = isolationSession,
            configs = listOf(RetrievalConfig.HYBRID_GLOBAL)
        ).first()

        val latencyA1 = passA1.latencyRerankMs ?: 0L
        val latencyB = passB.latencyRerankMs ?: 0L
        val latencyA2 = passA2.latencyRerankMs ?: 0L

        Log.i(
            TAG,
            "Cache Isolation Latencies for '$isolationQuery': " +
            "Pass A1 (HYBRID_GLOBAL)=$latencyA1 ms, " +
            "Pass B (HYBRID_PER_TYPE)=$latencyB ms, " +
            "Pass A2 (HYBRID_GLOBAL)=$latencyA2 ms"
        )

        // If cache isolation works, Pass A2 must NOT be an instant 0ms/1ms cache hit.
        // It must perform genuine cross-encoder inference (e.g. > 50ms)
        assertTrue(
            "Pass A2 rerank latency must reflect cold model evaluation (> 50ms), got $latencyA2 ms",
            latencyA2 >= 50L
        )

        // 9. Verification: Timeout Handling Verification
        Log.i(TAG, "=== STARTING TIMEOUT VERIFICATION ===")
        container.crossEncoderReranker.clearCache()
        val timeoutConfig = RetrievalConfig.CLEAN.copy(maxRerankTimeMs = 1L)
        val timeoutOutcome = container.searchEngine.search("marksheet", timeoutConfig)
        Log.i(
            TAG,
            "Forced timeout check for 'marksheet' (maxRerankTimeMs=1L): rerankTimedOut=${timeoutOutcome.rerankTimedOut}, " +
            "rerankLatencyMs=${timeoutOutcome.rerankLatencyMs}, totalLatencyMs=${timeoutOutcome.totalLatencyMs}"
        )
        assertTrue("Reranking timeout must be detected when maxRerankTimeMs is exceeded", timeoutOutcome.rerankTimedOut)

        val timeoutRuns = benchmarkRunner.runBenchmarkSuite(
            query = "marksheet",
            runSessionId = "timeout-verify-${System.currentTimeMillis()}",
            configs = listOf(timeoutConfig)
        )
        assertTrue("BenchmarkRunner must skip recording row when neural rerank times out", timeoutRuns.isEmpty())
        Log.i(TAG, "Timeout handling verified: record was successfully skipped when timeout occurred.")

        // 10. Verification: Repeatability (Run 1 vs Run 2 on same query with CLEAN)
        Log.i(TAG, "=== STARTING REPEATABILITY TEST ===")
        val repQuery = "marksheet"
        val repSession = "repeatability-${System.currentTimeMillis()}"

        val rep1 = benchmarkRunner.runBenchmarkSuite(
            query = repQuery,
            runSessionId = repSession,
            configs = listOf(RetrievalConfig.CLEAN)
        ).first()

        val rep2 = benchmarkRunner.runBenchmarkSuite(
            query = repQuery,
            runSessionId = repSession,
            configs = listOf(RetrievalConfig.CLEAN)
        ).first()

        Log.i(TAG, "Repeatability Run 1 IDs: ${rep1.resultIdsJson}")
        Log.i(TAG, "Repeatability Run 2 IDs: ${rep2.resultIdsJson}")
        assertEquals(
            "Result IDs and ranking order must be 100% deterministic between repeated runs",
            rep1.resultIdsJson,
            rep2.resultIdsJson
        )

        // 11. Export JSON for forensic record
        val exportedJson = BenchmarkLogger.exportToJson(context)
        Log.i(TAG, "Verification run exported to: ${exportedJson.absolutePath} (${exportedJson.length()} bytes)")
        assertTrue(exportedJson.exists() && exportedJson.length() > 0)

        // Copy to external storage
        val extDest = File(context.getExternalFilesDir(null), "harness_verification_export.json")
        exportedJson.copyTo(extDest, overwrite = true)
        Log.i(TAG, "Export copied to external storage: ${extDest.absolutePath}")

        Log.i(TAG, "=== PHASE 8 HARNESS VERIFICATION COMPLETED SUCCESSFULLY ===")
        }
    }
}
