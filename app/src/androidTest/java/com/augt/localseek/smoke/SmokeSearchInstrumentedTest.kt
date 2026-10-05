package com.augt.localseek.smoke

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-phone smoke test of the shipped search path: five generic queries through [RetrievalConfig.SHIPPED] must return results
 * within one second each (after one untimed warm-up search that loads the models and the in-memory index).
 *
 * Read-only: no indexing, no writes. Logs only counts and latencies under tag SMOKE (never query text or results).
 *
 *   adb shell am instrument -w -e class com.augt.localseek.smoke.SmokeSearchInstrumentedTest \
 *     com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SmokeSearchInstrumentedTest {

    private val queries = listOf("pdf", "notes", "settings", "camera", "meeting")

    @Test
    fun shippedConfigAnswersGenericQueriesQuickly() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        val config = RetrievalConfig.SHIPPED
        assertEquals(DenseIndexType.AUTO, config.denseIndexType)

        val chunks = container.database.chunkDao().countAllChunks()
        Log.i("SMOKE", "corpus chunks=$chunks")
        assertTrue("the phone has no index to search", chunks > 0)

        container.searchEngine.search("warmup", config)   // untimed: model load + in-memory index build

        val failures = mutableListOf<String>()
        queries.forEachIndexed { i, q ->
            val start = System.nanoTime()
            val outcome = container.searchEngine.search(q, config)
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.i("SMOKE", "query#${i + 1} results=${outcome.results.size} latencyMs=$ms")
            if (outcome.results.isEmpty()) failures += "query#${i + 1}: no results"
            if (ms >= 1000) failures += "query#${i + 1}: ${ms} ms (limit 1000)"
        }
        val status = container.autoVectorIndex.status()
        Log.i("SMOKE", "dense path=${status.path} chunkCount=${status.vectorCount}")
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}
