package com.augt.localseek.search.vector

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LshDeterminismTest {

    private class FakeBatteryMonitor(context: Context, var batteryLevel: Int) : BatteryMonitor(context) {
        override fun getCurrentBatteryLevel(): Int = batteryLevel
    }

    private fun generateRandomVector(dim: Int, seed: Int): FloatArray {
        val rng = Random(seed)
        val vec = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
        // Normalize to unit length
        var norm = 0.0
        for (v in vec) norm += (v * v).toDouble()
        val inv = (1.0 / kotlin.math.sqrt(norm)).toFloat()
        for (i in vec.indices) vec[i] *= inv
        return vec
    }

    @Test
    fun `LSH search produces identical outputs across differing battery levels when adaptiveLsh is false`() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("lsh_test").toFile()
        tempDir.deleteOnExit()
        val mockContext = mockk<Context>(relaxed = true)
        io.mockk.every { mockContext.filesDir } returns tempDir
        val lsh = LshIndexManager(mockContext)

        // Populate index with 100 deterministic vectors
        val vectors = (1..100).map { id ->
            id.toLong() to generateRandomVector(384, seed = id)
        }
        lsh.buildIndex(vectors)
        assertTrue(lsh.isInitialized)

        val queryVec = generateRandomVector(384, seed = 9999)

        val fakeBattery = FakeBatteryMonitor(mockContext, batteryLevel = 95)
        lsh.batteryMonitor = fakeBattery

        // Run at 95% battery (NORMAL)
        fakeBattery.batteryLevel = 95
        val resultsAt95 = lsh.search(queryVec, topK = 10, adaptiveLsh = false)

        // Run at 45% battery (LOW)
        fakeBattery.batteryLevel = 45
        val resultsAt45 = lsh.search(queryVec, topK = 10, adaptiveLsh = false)

        // Run at 15% battery (CRITICAL)
        fakeBattery.batteryLevel = 15
        val resultsAt15 = lsh.search(queryVec, topK = 10, adaptiveLsh = false)

        assertTrue("Results must not be empty", resultsAt95.isNotEmpty())
        assertEquals("Results at 95% and 45% must be identical", resultsAt95, resultsAt45)
        assertEquals("Results at 95% and 15% must be identical", resultsAt95, resultsAt15)

        for (i in resultsAt95.indices) {
            assertEquals("ID at rank $i must match", resultsAt95[i].id, resultsAt15[i].id)
            assertEquals("Score at rank $i must match", resultsAt95[i].score, resultsAt15[i].score, 0.000001f)
        }
    }

    @Test
    fun `LSH search adapts candidate search space when adaptiveLsh is explicitly enabled`() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("lsh_test2").toFile()
        tempDir.deleteOnExit()
        val mockContext = mockk<Context>(relaxed = true)
        io.mockk.every { mockContext.filesDir } returns tempDir
        val lsh = LshIndexManager(mockContext)

        // Populate index with 150 deterministic vectors
        val vectors = (1..150).map { id ->
            id.toLong() to generateRandomVector(384, seed = id)
        }
        lsh.buildIndex(vectors)

        val queryVec = generateRandomVector(384, seed = 8888)

        val fakeBattery = FakeBatteryMonitor(mockContext, batteryLevel = 95)
        lsh.batteryMonitor = fakeBattery

        val baseConfig = LshConfig.forDatasetSize(150)
        val configAt95 = LshConfig.forBatteryLevel(baseConfig, 95)
        val configAt15 = LshConfig.forBatteryLevel(baseConfig, 15)

        // Verify configuration differs across battery bands
        assertTrue("Critical battery must probe fewer tables", configAt15.numTables <= configAt95.numTables)
        assertTrue("Critical battery must evaluate fewer search candidates", configAt15.searchCandidates < configAt95.searchCandidates)
    }
}
