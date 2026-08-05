package com.augt.localseek.retrieval

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentChunk
import com.augt.localseek.search.vector.BruteForceVectorIndex
import com.augt.localseek.search.vector.LshIndexManager
import com.augt.localseek.search.vector.LshVectorIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class VectorIndexRecallTest {

    private lateinit var db: AppDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun computeRecallAtK_Clustered(): Unit = runBlocking {
        val chunkDao = db.chunkDao()
        val dim = 384
        val numVectors = 500
        val k = 20
        
        val random = Random(42)
        
        // 1. Generate Clustered Embeddings
        // pick a target vector
        val targetVec = FloatArray(dim) { random.nextFloat() * 2f - 1f }.let { l2Normalize(it) }
        
        // create 30 vectors very close to target (noise level 0.01)
        val closeVectors = (1..30).map { i ->
            val noisy = FloatArray(dim) { j -> targetVec[j] + random.nextFloat() * 0.01f }
            l2Normalize(noisy)
        }
        
        // create rest as random noise
        val randomVectors = (1..(numVectors - 31)).map { 
            l2Normalize(FloatArray(dim) { random.nextFloat() * 2f - 1f })
        }
        
        val allVecs = listOf(targetVec) + closeVectors + randomVectors
        
        val embeddings = allVecs.mapIndexed { i, vec ->
            DocumentChunk(
                parentFileId = 1L,
                chunkIndex = i,
                text = "Chunk $i",
                startOffset = 0,
                endOffset = 10,
                embedding = vec,
                createdAt = System.currentTimeMillis()
            )
        }
        chunkDao.insertAll(embeddings)
        
        // 2. Setup Indices
        val bruteForce = BruteForceVectorIndex(chunkDao)
        val lshManager = LshIndexManager(context)
        val lsh = LshVectorIndex(lshManager, chunkDao)
        
        // Build LSH index
        val allFromDb = chunkDao.getEmbeddingsPage(numVectors, 0)
        assertEquals("DB should have $numVectors vectors", numVectors, allFromDb.size)
        lsh.buildIndex(allFromDb)
        
        // 3. Query with the target vector itself (should be top match)
        val bfResults = bruteForce.search(targetVec, k)
        val lshResults = lsh.search(targetVec, k)
        
        // 4. Log Raw Results
        android.util.Log.e("RECALL_DEBUG", "--- BRUTE FORCE TOP 5 ---")
        bfResults.take(5).forEach { 
            android.util.Log.e("RECALL_DEBUG", "ID: ${it.id} (Type: ${it.id::class.java.simpleName}), Score: ${it.score}")
        }
        
        android.util.Log.e("RECALL_DEBUG", "--- LSH TOP 5 ---")
        if (lshResults.isEmpty()) {
            android.util.Log.e("RECALL_DEBUG", "LSH RETURNED ZERO RESULTS")
        }
        lshResults.take(5).forEach { 
            android.util.Log.e("RECALL_DEBUG", "ID: ${it.id} (Type: ${it.id::class.java.simpleName}), Score: ${it.score}")
        }
        
        // 5. Compute Recall@K
        val bfIds = bfResults.map { it.id }.toSet()
        val lshIds = lshResults.map { it.id }.toSet()
        
        val overlap = lshIds.intersect(bfIds).size
        val recallAtK = overlap.toFloat() / k
        
        val summary = StringBuilder()
        summary.append("Recall@$k: $recallAtK ($overlap / $k)\n")
        summary.append("BF TOP 5: ${bfResults.take(5).map { "${it.id}(${String.format("%.3f", it.score)})" }}\n")
        summary.append("LSH TOP 5: ${lshResults.take(5).map { "${it.id}(${String.format("%.3f", it.score)})" }}\n")
        
        assertTrue("RECALL_DUMP:\n$summary", false)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = Math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }
}
