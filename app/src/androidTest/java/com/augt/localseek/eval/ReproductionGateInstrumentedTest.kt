package com.augt.localseek.eval

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.di.AppContainer
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.ml.TokenizerMode
import com.augt.localseek.retrieval.FusionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReproductionGateInstrumentedTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var database: AppDatabase
    private lateinit var benchmarkRunner: BenchmarkRunner

    companion object {
        private const val TAG = "ReproductionGate"

        val HISTORICAL_QUERIES = listOf(
            "aadhar",
            "adhar",
            "anime",
            "archit",
            "driver",
            "game",
            "geogu",
            "geoguesse",
            "geoguessee",
            "gmail",
            "intern",
            "kingdom",
            "contact_b",
            "madokami",
            "marksheet",
            "phone",
            "contact_f",
            "contact_alpha",
            "timetable",
            "whastapp",
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

    @Test
    fun executeReproductionBenchmark() {
        runBlocking {
            val runSessionId = "reproduction-gate-${System.currentTimeMillis()}"
            Log.i(TAG, "Starting Phase 7 Reproduction Gate Benchmark runSessionId=$runSessionId")

        // 1. Verify corpus and clear previous benchmark logs to ensure clean capture
        val chunkCount = database.chunkDao().countAllChunks()
        Log.i(TAG, "Corpus verification: chunks=$chunkCount, apps=${database.appDao().getCount()}, contacts=${database.contactDao().getCount()}")
        assertTrue("Expected database chunks to be populated", chunkCount > 0)

        container.denseRetriever.initializeIndex()
        Log.i(TAG, "LSH index initialized: vectors=${container.lshIndexManager.indexedVectorCount}")

        val dao = database.benchmarkRunDao()
        dao.clearAll()
        assertEquals(0, dao.getCount())

        // 2. Define the exact LEGACY configurations matching Phase 24 historical baseline
        val legacyConfigs = listOf(
            // Primary LEGACY preset requested by the plan
            RetrievalConfig.LEGACY,

            // 6-arm historical breakdown under identical legacy parameters
            RetrievalConfig.BM25.copy(
                symmetricFallback = false,
                includeSynonymsInBm25 = true,
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "bm25"
            ),
            RetrievalConfig.DENSE_LSH.copy(
                adaptiveLsh = true,
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "dense_lsh"
            ),
            RetrievalConfig.DENSE_BRUTE_FORCE.copy(
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "dense_bruteforce"
            ),
            RetrievalConfig.HYBRID_GLOBAL.copy(
                adaptiveLsh = true,
                typePriorEnabled = true,
                perSpaceNorm = false,
                rerankBeforeDiversify = false,
                symmetricFallback = false,
                includeSynonymsInBm25 = true,
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "hybrid_global"
            ),
            RetrievalConfig.HYBRID_PER_TYPE.copy(
                adaptiveLsh = true,
                typePriorEnabled = true,
                perSpaceNorm = false,
                rerankBeforeDiversify = false,
                symmetricFallback = false,
                includeSynonymsInBm25 = true,
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "hybrid_per_type"
            ),
            RetrievalConfig.HYBRID_THRESHOLD.copy(
                adaptiveLsh = true,
                typePriorEnabled = true,
                perSpaceNorm = false,
                rerankBeforeDiversify = false,
                symmetricFallback = false,
                includeSynonymsInBm25 = true,
                tokenizerMode = TokenizerMode.LEGACY,
                presetName = "hybrid_threshold"
            )
        )

        Log.i(TAG, "Executing ${HISTORICAL_QUERIES.size} queries across ${legacyConfigs.size} configs...")

        var totalRuns = 0
        HISTORICAL_QUERIES.forEachIndexed { qIdx, queryText ->
            Log.d(TAG, "[$qIdx/${HISTORICAL_QUERIES.size}] Benchmarking query: '$queryText'")
            val runs = benchmarkRunner.runBenchmarkSuite(
                query = queryText,
                runSessionId = runSessionId,
                configs = legacyConfigs
            )
            totalRuns += runs.size
            Log.d(TAG, "Recorded ${runs.size} runs for query '$queryText'")
        }

        val storedCount = dao.getCount()
        Log.i(TAG, "Reproduction benchmark finished. Total stored records in DAO: $storedCount")
        assertTrue("Expected records stored in DAO, got $storedCount", storedCount > 0)

        // 3. Export benchmark JSON
        val jsonFile = BenchmarkLogger.exportToJson(context)
        Log.i(TAG, "Benchmark exported to: ${jsonFile.absolutePath} (size=${jsonFile.length()} bytes)")
        assertTrue(jsonFile.exists() && jsonFile.length() > 0)

        // Also copy export to external files dir or download for easy adb pull
        try {
            val downloadExport = File("/sdcard/Download/reproduction_benchmark_export.json")
            jsonFile.copyTo(downloadExport, overwrite = true)
            Log.i(TAG, "Benchmark export copied to: ${downloadExport.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed copying to /sdcard/Download: ${e.message}")
        }
        val extExport = File(context.getExternalFilesDir(null), "reproduction_benchmark_export.json")
        jsonFile.copyTo(extExport, overwrite = true)
        Log.i(TAG, "Benchmark export copied to: ${extExport.absolutePath}")
        }
    }
}
