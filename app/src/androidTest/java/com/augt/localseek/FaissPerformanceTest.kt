package com.augt.localseek

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.retrieval.DenseRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaissPerformanceTest {

    @Test
    fun compareLshVsBruteForce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.getInstance(context)
        val retriever = DenseRetriever(context)

        retriever.rebuildIndex()

        val hasEmbeddings = db.chunkDao().getEmbeddingsPage(limit = 1, offset = 0).isNotEmpty()
        assumeTrue("No embeddings available; skipping LSH perf assertion", hasEmbeddings)

        val testQueries = listOf(
            "machine learning algorithms",
            "kotlin coroutines",
            "database optimization"
        )

        testQueries.forEach { query ->
            val start = System.currentTimeMillis()
            val results = retriever.search(query, topK = 50)
            val duration = System.currentTimeMillis() - start

            android.util.Log.d("FaissPerformanceTest", "LSH query='$query' duration=${duration}ms results=${results.size}")
            assertTrue("Expected <300ms for query '$query' but got ${duration}ms", duration < 300)
        }

        retriever.close()
    }
}

