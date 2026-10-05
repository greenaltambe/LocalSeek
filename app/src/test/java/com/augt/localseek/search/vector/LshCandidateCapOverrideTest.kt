package com.augt.localseek.search.vector

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LshCandidateCapOverrideTest {

    private fun vec(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(384) { r.nextFloat() * 2f - 1f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
        return v
    }

    private fun manager(): LshIndexManager {
        val dir = java.nio.file.Files.createTempDirectory("lsh_cap").toFile().also { it.deleteOnExit() }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns dir
        return LshIndexManager(ctx)
    }

    // One table, 1 bit, no probing: two huge buckets, so a small cap is clearly visible.
    private val config = LshConfig(1, 1, 64, 20, LshConfig.MemoryMode.IN_MEMORY, 0)

    @Test
    fun `default cap keeps the index configuration, positive overrides, negative removes the cap`() = runBlocking {
        val m = manager()
        m.buildIndex((1..2000).map { it.toLong() to vec(it) }, config)
        val q = vec(424242)
        val capped = m.search(q, topK = 5000)
        val cap50 = m.search(q, topK = 5000, candidateCapOverride = 50)
        val uncapped = m.search(q, topK = 5000, candidateCapOverride = -1)
        assertEquals(20, capped.size)
        assertEquals(50, cap50.size)
        assertTrue("uncapped scores the whole bucket", uncapped.size > 500)
        assertEquals(m.search(q, topK = 5000, candidateCapOverride = 0), capped)
        assertTrue(uncapped.map { it.score } == uncapped.map { it.score }.sortedDescending())
    }
}
