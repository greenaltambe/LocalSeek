package com.augt.localseek.smoke

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory use of the shipped search path (task AT part 5.2): 20 generic searches (the five smoke queries, four rounds) through
 * [RetrievalConfig.SHIPPED]. Logs the process's own memory (Debug.MemoryInfo summary: total PSS, Java heap, native heap, in KB)
 * at idle, after the warm-up search and after each round, under tag MEMPROBE. Read-only: no indexing, no writes; no query text.
 *
 *   adb shell am instrument -w -e class com.augt.localseek.smoke.MemorySearchInstrumentedTest \
 *     com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class MemorySearchInstrumentedTest {

    private val queries = listOf("pdf", "notes", "settings", "camera", "meeting")

    private fun log(phase: String) {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun stat(key: String) = info.getMemoryStat(key)?.toLongOrNull() ?: -1L
        Log.i("MEMPROBE", "phase=$phase totalPssKb=${stat("summary.total-pss")} javaHeapKb=${stat("summary.java-heap")} " +
            "nativeHeapKb=${stat("summary.native-heap")} graphicsKb=${stat("summary.graphics")} " +
            "javaUsedKb=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024}")
    }

    @Test
    fun memoryDuringTwentyShippedSearches() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        val config = RetrievalConfig.SHIPPED
        val chunks = container.database.chunkDao().countAllChunks()
        assertTrue("the phone has no index to search", chunks > 0)
        Log.i("MEMPROBE", "corpus chunks=$chunks")

        log("idle")
        container.searchEngine.search("warmup", config)
        log("after-warmup")
        var results = 0
        repeat(4) { round ->
            queries.forEach { q -> results += container.searchEngine.search(q, config).results.size }
            log("after-round-${round + 1}")
        }
        val status = container.autoVectorIndex.status()
        Log.i("MEMPROBE", "dense path=${status.path} vectors=${status.vectorCount} float32Bytes=${status.vectorCount.toLong() * 384 * 4} searches=20 results=$results")
        assertTrue("no results at all", results > 0)
    }
}
