package com.augt.localseek

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.logging.BenchmarkLogger
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BenchmarkInfrastructureTest {

    private lateinit var db: AppDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun createDb() {
        db = AppDatabase.getInstance(context)
    }

    @Test
    fun generateAndLogRealMultiBackendData(): Unit = runBlocking {
        val dao = db.benchmarkRunDao()
        dao.clearAll()
        
        val sessionId = "benchmark-mode-session"
        val queryText = "rajkumar evaluation"
        val queryId = queryText.hashCode().toString()
        
        val backends = listOf("bm25", "dense_lsh", "hybrid_global", "hybrid_per_type", "hybrid_threshold")
        
        backends.forEachIndexed { index, backend ->
            val record = BenchmarkRunEntity(
                runSessionId = sessionId,
                queryId = queryId,
                queryText = queryText,
                timestamp = System.currentTimeMillis() + index,
                deviceModel = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
                backend = backend,
                corpusSizeChunks = 2100,
                corpusSizeApps = 101,
                corpusSizeContacts = 918,
                latencyBm25Ms = 45,
                latencyDenseMs = 400,
                latencyFusionMs = 30,
                latencyRerankMs = 150,
                latencyTotalMs = 625,
                memoryMbPeak = 145.2f,
                batteryPctBefore = 95,
                batteryPctAfter = 94,
                resultIdsJson = "[\"CONTACT:789\",\"FILE:101\"]",
                resultScoresJson = "[0.95,0.65]",
                resultEntityTypesJson = "[\"CONTACT\",\"FILE\"]"
            )
            dao.insert(record)
        }
        
        val csvFile = BenchmarkLogger.exportToCsv(context)
        val jsonFile = BenchmarkLogger.exportToJson(context)
        
        fun logLong(tag: String, msg: String) {
            msg.chunked(4000).forEach { android.util.Log.e(tag, it) }
        }
        
        logLong("SANITY_CHECK_CSV", csvFile.readText())
        logLong("SANITY_CHECK_JSON", jsonFile.readText())
        
        csvFile.delete()
        jsonFile.delete()
    }
}
