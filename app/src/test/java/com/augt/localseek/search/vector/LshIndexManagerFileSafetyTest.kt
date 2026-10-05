package com.augt.localseek.search.vector

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Production no longer builds or loads the LSH index; the benchmark arms still can, and no path may touch an existing lsh_index.bin. */
class LshIndexManagerFileSafetyTest {

    private fun vec(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(384) { r.nextFloat() * 2f - 1f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
        return v
    }

    private fun dir() = java.nio.file.Files.createTempDirectory("lsh_safety").toFile().also { it.deleteOnExit() }

    private fun ctx(dir: File) = mockk<Context>(relaxed = true).also { every { it.filesDir } returns dir }

    @Test
    fun `a default manager builds and searches in memory and writes nothing`() = runBlocking {
        val d = dir()
        val m = LshIndexManager(ctx(d))
        m.buildIndex((1..300).map { it.toLong() to vec(it) })
        assertTrue(m.isInitialized)
        assertEquals(300, m.indexedVectorCount)
        assertTrue(m.search(vec(1), topK = 5).isNotEmpty())   // the benchmark LSH arms (E3, E5, E12) keep working
        assertEquals(0, d.listFiles()!!.size)
    }

    @Test
    fun `an existing index file is loaded and left byte-for-byte untouched by builds and clear`() = runBlocking {
        val d = dir()
        LshIndexManager(ctx(d), persistIndex = true).buildIndex((1..200).map { it.toLong() to vec(it) })
        val file = File(d, "lsh_index.bin")
        assertTrue(file.exists())
        val before = file.readBytes()

        val m = LshIndexManager(ctx(d))
        assertTrue(m.loadIndex())                       // the harness loads the existing file
        assertEquals(200, m.indexedVectorCount)
        m.buildIndex((1..50).map { it.toLong() to vec(it + 1000) })   // an in-memory rebuild must not overwrite it
        m.clearIndex()                                  // and clearing must not delete it
        assertTrue(file.exists())
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `only an explicitly persisting manager deletes the file on clear`() = runBlocking {
        val d = dir()
        val m = LshIndexManager(ctx(d), persistIndex = true)
        m.buildIndex((1..60).map { it.toLong() to vec(it) })
        assertTrue(File(d, "lsh_index.bin").exists())
        m.clearIndex()
        assertFalse(File(d, "lsh_index.bin").exists())
    }
}
