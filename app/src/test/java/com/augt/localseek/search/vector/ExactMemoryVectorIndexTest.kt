package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import com.augt.localseek.ml.VectorUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExactMemoryVectorIndexTest {

    private fun vec(seed: Int, normalise: Boolean = true): FloatArray {
        val r = Random(seed)
        val v = FloatArray(384) { r.nextFloat() * 2f - 1f }
        if (normalise) {
            val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            for (i in v.indices) v[i] /= n
        }
        return v
    }

    private class FakeDb(var rows: List<ChunkEmbedding>) {
        val dao: ChunkDao = mockk(relaxed = true)
        init {
            coEvery { dao.countAllChunks() } answers { rows.size }
            coEvery { dao.getEmbeddingsPage(any(), any()) } answers {
                val limit = firstArg<Int>()
                val last = secondArg<Long>()
                rows.filter { it.id > last }.sortedBy { it.id }.take(limit)
            }
        }
    }

    private fun rows(n: Int) = (1..n).map { ChunkEmbedding(it.toLong() * 3, vec(it)) }

    @Test
    fun `ranking and scores are identical to the database path`() = runBlocking {
        val db = FakeDb(rows(700))
        val exact = BruteForceVectorIndex(db.dao)
        val mem = ExactMemoryVectorIndex(db.dao, fallback = exact)
        for (q in 5000..5009) {
            val query = vec(q)
            val expected = exact.search(query, 50)
            val actual = mem.search(query, 50)
            assertEquals(expected.map { it.id }, actual.map { it.id })
            assertEquals(expected.map { it.score }, actual.map { it.score })   // bit-identical
        }
    }

    @Test
    fun `unnormalised vectors still give the cosine of the database path`() = runBlocking {
        val db = FakeDb((1..200).map { ChunkEmbedding(it.toLong(), vec(it, normalise = false)) })
        val exact = BruteForceVectorIndex(db.dao)
        val mem = ExactMemoryVectorIndex(db.dao)
        val q = vec(9001, normalise = false)
        assertEquals(exact.search(q, 20), mem.search(q, 20))
    }

    @Test
    fun `tie rule is score descending then chunk id ascending`() = runBlocking {
        val same = vec(1)
        val db = FakeDb(listOf(ChunkEmbedding(30, same), ChunkEmbedding(10, same), ChunkEmbedding(20, same), ChunkEmbedding(40, vec(2))))
        val mem = ExactMemoryVectorIndex(db.dao)
        val top3 = mem.search(same, 3)
        assertEquals(listOf(10L, 20L, 30L), top3.map { it.id })
        val top2 = mem.search(same, 2)
        assertEquals(listOf(10L, 20L), top2.map { it.id })   // boundary tie keeps the lowest ids
    }

    @Test
    fun `fewer chunks than k returns all of them and empty index returns nothing`() = runBlocking {
        val db = FakeDb(rows(7))
        val mem = ExactMemoryVectorIndex(db.dao)
        assertEquals(7, mem.search(vec(99), 50).size)
        assertTrue(mem.search(vec(99), 0).isEmpty())
        val empty = ExactMemoryVectorIndex(FakeDb(emptyList()).dao)
        assertTrue(empty.search(vec(99), 10).isEmpty())
        assertTrue(mem.search(FloatArray(10), 10).isEmpty())   // wrong query dimension
    }

    @Test
    fun `zero-length and wrong-size embeddings are skipped and do not crash`() = runBlocking {
        val good = rows(20)
        val bad = listOf(
            ChunkEmbedding(1000, FloatArray(0)),
            ChunkEmbedding(1001, FloatArray(100)),
            ChunkEmbedding(1002, FloatArray(384))   // all zeros: scored 0, same as the database path
        )
        val db = FakeDb(good + bad)
        val mem = ExactMemoryVectorIndex(db.dao)
        val res = mem.search(vec(777), 100)
        assertEquals(21, res.size)
        assertTrue(res.none { it.id == 1000L || it.id == 1001L })
        assertEquals(0f, res.first { it.id == 1002L }.score, 0f)
        assertEquals(21, mem.rowCount)
    }

    @Test
    fun `index is built once and rebuilt only when the version changes or after invalidate`() = runBlocking {
        val db = FakeDb(rows(50))
        var version = 1L
        val mem = ExactMemoryVectorIndex(db.dao, versionProvider = { version })
        mem.search(vec(1), 5); mem.search(vec(2), 5); mem.search(vec(3), 5)
        assertEquals(1, mem.buildCount)
        db.rows = rows(60)
        mem.search(vec(1), 5)
        assertEquals("stale until the version changes", 1, mem.buildCount)
        assertEquals(50, mem.rowCount)
        version = 2L
        assertEquals(60, mem.search(vec(1), 100).size)
        assertEquals(2, mem.buildCount)
        mem.invalidate()
        mem.search(vec(1), 5)
        assertEquals(3, mem.buildCount)
    }

    @Test
    fun `concurrent first searches build once`() = runBlocking {
        val db = FakeDb(rows(300))
        val mem = ExactMemoryVectorIndex(db.dao)
        val results = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { mem.search(vec(it), 10) } }.awaitAll()
        assertEquals(1, mem.buildCount)
        assertTrue(results.all { it.size == 10 })
    }

    @Test
    fun `size guard falls back to the database path and logs`() = runBlocking {
        val db = FakeDb(rows(100))
        val logs = mutableListOf<String>()
        val fallbackCalls = mutableListOf<Int>()
        val fallback = object : VectorIndex {
            override val backendName = "fake"
            override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> { fallbackCalls.add(k); return listOf(ScoredResult(-1, 1f)) }
            override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {}
        }
        val mem = ExactMemoryVectorIndex(db.dao, fallback = fallback, maxBytes = 1000, debugLog = { logs.add(it) })
        assertEquals(listOf(-1L), mem.search(vec(1), 5).map { it.id })
        mem.search(vec(2), 7)
        assertEquals(listOf(5, 7), fallbackCalls)
        assertEquals(0, mem.buildCount)
        assertEquals(1, logs.size)   // logged once per version, not per query
        coVerify(exactly = 0) { db.dao.getEmbeddingsPage(any(), any()) }
    }

    @Test
    fun `cosine matches VectorUtils for a hand-checkable case`() = runBlocking {
        val a = FloatArray(384).also { it[0] = 1f }
        val b = FloatArray(384).also { it[0] = 1f; it[1] = 1f }
        val db = FakeDb(listOf(ChunkEmbedding(1, b)))
        val score = ExactMemoryVectorIndex(db.dao).search(a, 1).single().score
        assertEquals(VectorUtils.cosineSimilarity(a, b), score, 0f)
        assertEquals(0.7071f, score, 1e-3f)
    }

    @Test
    fun `scan of a corpus-sized index is fast`() = runBlocking {
        val db = FakeDb((1..14000).map { ChunkEmbedding(it.toLong(), vec(it)) })
        val mem = ExactMemoryVectorIndex(db.dao)
        val t0 = System.nanoTime(); mem.search(vec(1), 10); val build = (System.nanoTime() - t0) / 1_000_000
        mem.search(vec(2), 10)
        val t1 = System.nanoTime(); repeat(5) { mem.search(vec(3 + it), 50) }; val scan = (System.nanoTime() - t1) / 5_000_000
        println("EXACT_MEMORY_TIMING rows=14000 first_search_incl_build_ms=$build warm_scan_ms=$scan (host JVM, fake DAO)")
        assertTrue("warm scan took $scan ms", scan < 1000)
    }
}
