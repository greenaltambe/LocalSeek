package com.augt.localseek

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.DocumentChunk
import com.augt.localseek.data.DocumentEntity
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.ResultAggregator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class SearchE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setup() = runBlocking {
        db = AppDatabase.getInstance(context)
        seedSampleDocuments()
    }

    @Test
    fun searchReturnsRelevantResults() = runBlocking {
        val retriever = BM25Retriever(context)
        val query = "machine learning"
        val chunkResults = retriever.search(query, 50)
        val fileResults = ResultAggregator.aggregateToFiles(chunkResults, query)

        assertFalse(fileResults.isEmpty())
        assertTrue(fileResults.first().title.contains("ml", ignoreCase = true))
    }

    @Test
    fun searchLatencyUnder300ms() = runBlocking {
        val retriever = BM25Retriever(context)
        val query = "machine learning"

        val duration = measureTimeMillis {
            runBlocking {
                retriever.search(query, 50)
            }
        }

        assertTrue("Expected <300ms but got ${duration}ms", duration < 300)
    }

    @Test
    fun noMemoryLeakOnRepeatedSearches() = runBlocking {
        val retriever = BM25Retriever(context)
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        repeat(100) {
            retriever.search("query $it", 20)
        }

        runtime.gc()
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = finalMemory - initialMemory

        assertTrue("Memory increase too high: ${memoryIncrease} bytes", memoryIncrease < 50L * 1024L * 1024L)
    }

    private suspend fun seedSampleDocuments() {
        val now = System.currentTimeMillis()
        val unique = now.toString()

        val docs = listOf(
            Triple("/test/ml_intro_$unique.pdf", "ML Intro Guide", "machine learning basics neural networks supervised learning"),
            Triple("/test/kotlin_$unique.txt", "Kotlin Coroutines", "kotlin coroutines flow structured concurrency async"),
            Triple("/test/random_$unique.md", "Random Notes", "gardening recipes travel notes")
        )

        docs.forEachIndexed { idx, (path, title, body) ->
            val docId = db.documentDao().insert(
                DocumentEntity(
                    filePath = path,
                    title = title,
                    body = "",
                    fileType = path.substringAfterLast('.'),
                    modifiedAt = now - idx * 1_000,
                    sizeBytes = body.length.toLong(),
                    embedding = null
                )
            )

            db.chunkDao().insertAll(
                listOf(
                    DocumentChunk(
                        parentFileId = docId,
                        chunkIndex = 0,
                        text = body,
                        startOffset = 0,
                        endOffset = body.length
                    )
                )
            )
        }
    }
}

