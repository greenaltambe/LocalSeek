package com.augt.localseek.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.search.vector.BruteForceVectorIndex
import com.augt.localseek.search.vector.LshIndexManager
import com.augt.localseek.search.vector.LshVectorIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealCorpusRecallTest {

    private lateinit var db: AppDatabase
    private lateinit var encoder: DenseEncoder
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        db = AppDatabase.getInstance(context)
        encoder = DenseEncoder(context)
    }

    @After
    fun cleanup() {
        encoder.close()
    }

    @Test
    fun measureRealCorpusRecall(): Unit = runBlocking {
        val chunkDao = db.chunkDao()
        val totalChunks = chunkDao.countAllChunks()
        if (totalChunks == 0) {
            android.util.Log.e("RECALL_MEASURE", "No chunks indexed. Run indexing first!")
            return@runBlocking
        }

        val lshManager = LshIndexManager(context)
        val lsh = LshVectorIndex(lshManager, chunkDao)
        val bruteForce = BruteForceVectorIndex(chunkDao)

        // Ensure LSH is loaded/built
        val loaded = lshManager.loadIndex()
        if (!loaded) {
            android.util.Log.i("RECALL_MEASURE", "Building LSH index for $totalChunks chunks...")
            lshManager.rebuildFromDatabase(chunkDao)
        }

        val queries = listOf(
            "whatsapp",
            "rajkumar tambe",
            "madokami",
            "load of bread",
            "machine learning",
            "android development",
            "resume",
            "personal notes"
        )

        val results = mutableListOf<QueryResult>()

        for (query in queries) {
            val queryVec = encoder.encode(query)
            if (queryVec.isEmpty()) continue

            // Measure Brute Force
            val bfStart = System.currentTimeMillis()
            val bf10 = bruteForce.search(queryVec, 10)
            val bf20 = bruteForce.search(queryVec, 20)
            val bfTime = System.currentTimeMillis() - bfStart

            // Measure LSH
            val lshStart = System.currentTimeMillis()
            val lsh10 = lsh.search(queryVec, 10)
            val lsh20 = lsh.search(queryVec, 20)
            val lshTime = System.currentTimeMillis() - lshStart

            val recall10 = computeRecall(bf10.map { it.id }.toSet(), lsh10.map { it.id }.toSet(), 10)
            val recall20 = computeRecall(bf20.map { it.id }.toSet(), lsh20.map { it.id }.toSet(), 20)

            results.add(QueryResult(query, recall10, recall20, bfTime, lshTime))
        }

        // Final Report
        val avgRecall10 = results.map { it.recall10 }.average()
        val avgRecall20 = results.map { it.recall20 }.average()
        val avgBfTime = results.map { it.bfTime }.average()
        val avgLshTime = results.map { it.lshTime }.average()

        val report = StringBuilder()
        report.append("\n=== REAL CORPUS RECALL REPORT (Size: $totalChunks chunks) ===\n")
        report.append(String.format("%-20s | %-10s | %-10s | %-8s | %-8s\n", "Query", "R@10", "R@20", "BF(ms)", "LSH(ms)"))
        report.append("-".repeat(65) + "\n")
        results.forEach {
            report.append(String.format("%-20s | %-10.2f | %-10.2f | %-8d | %-8d\n", 
                it.query, it.recall10, it.recall20, it.bfTime, it.lshTime))
        }
        report.append("-".repeat(65) + "\n")
        report.append(String.format("%-20s | %-10.2f | %-10.2f | %-8.1f | %-8.1f\n", 
            "AVERAGE", avgRecall10, avgRecall20, avgBfTime, avgLshTime))
        
        android.util.Log.println(android.util.Log.ASSERT, "RECALL_REPORT", report.toString())
        
        // Write to external files dir for reliable retrieval via adb shell cat
        val externalDir = context.getExternalFilesDir(null)
        val outputFile = java.io.File(externalDir, "real_recall_report.txt")
        outputFile.writeText(report.toString())
        android.util.Log.e("RECALL_MEASURE", "Report written to: ${outputFile.absolutePath}")

        assertTrue("Report printed", results.isNotEmpty())
    }

    private fun computeRecall(groundTruth: Set<Long>, approximate: Set<Long>, k: Int): Float {
        if (groundTruth.isEmpty()) return 1.0f
        val overlap = groundTruth.intersect(approximate).size
        return overlap.toFloat() / k
    }

    data class QueryResult(
        val query: String,
        val recall10: Float,
        val recall20: Float,
        val bfTime: Long,
        val lshTime: Long
    )
}
