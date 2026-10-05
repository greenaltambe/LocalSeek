package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutoVectorIndexTest {

    private fun vec(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(384) { r.nextFloat() * 2f - 1f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= n
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

    private fun auto(db: FakeDb, threshold: Int, kPrime: Int = 40, maxBytes: Long = AutoVectorIndex.DEFAULT_MAX_BYTES, version: suspend () -> Long = { 0L }): AutoVectorIndex {
        val exactDb = BruteForceVectorIndex(db.dao)
        return AutoVectorIndex(db.dao, exact = ExactMemoryVectorIndex(db.dao, fallback = exactDb), fallback = exactDb,
            exactMaxVectors = threshold, rescoreCandidates = kPrime, maxBytes = maxBytes, versionProvider = version)
    }

    @Test
    fun `below the threshold rankings and scores are identical to the exact in-memory index`() = runBlocking {
        val db = FakeDb(rows(250))
        val memory = ExactMemoryVectorIndex(db.dao, fallback = BruteForceVectorIndex(db.dao))
        val auto = auto(db, threshold = 300)
        for (q in 7000..7009) {
            val expected = memory.search(vec(q), 50)
            val actual = auto.search(vec(q), 50)
            assertEquals(expected.map { it.id }, actual.map { it.id })
            assertEquals(expected.map { it.score }, actual.map { it.score })   // bit-identical
        }
        assertEquals(AutoVectorIndex.Status(AutoVectorIndex.Path.EXACT_MEMORY, 250), auto.status())
        assertEquals(0, auto.binaryBuildCount)
    }

    @Test
    fun `at exactly the threshold the exact path is still used`() = runBlocking {
        val db = FakeDb(rows(120))
        val auto = auto(db, threshold = 120)
        auto.search(vec(1), 10)
        assertEquals(AutoVectorIndex.Path.EXACT_MEMORY, auto.status().path)
    }

    @Test
    fun `above the threshold the binary rescore index answers and matches a directly built one`() = runBlocking {
        val all = rows(400)
        val db = FakeDb(all)
        val auto = auto(db, threshold = 300, kPrime = 40)
        val direct = BinaryRescoreIndex(384, 40).also { idx ->
            idx.build(all.map { it.id }.toLongArray(), FloatArray(all.size * 384).also { f -> all.forEachIndexed { r, e -> System.arraycopy(e.embedding, 0, f, r * 384, 384) } })
        }
        for (q in 8000..8004) {
            val expected = direct.search(vec(q), 20)
            val actual = auto.search(vec(q), 20)
            assertEquals(expected.map { it.id }, actual.map { it.id })
            assertEquals(expected.map { it.score }, actual.map { it.score })
        }
        assertEquals(AutoVectorIndex.Status(AutoVectorIndex.Path.BINARY_RESCORE, 400), auto.status())
        assertEquals(1, auto.binaryBuildCount)   // built once, reused
    }

    @Test
    fun `the binary path keeps most of the exact top ten`() = runBlocking {
        val db = FakeDb(rows(600))
        val auto = auto(db, threshold = 100, kPrime = 200)
        val exact = BruteForceVectorIndex(db.dao)
        var hit = 0
        var total = 0
        for (q in 9000..9019) {
            val e = exact.search(vec(q), 10).map { it.id }.toSet()
            val a = auto.search(vec(q), 10).map { it.id }.toSet()
            hit += e.intersect(a).size
            total += e.size
        }
        assertTrue("recall@10 ${hit.toDouble() / total}", hit.toDouble() / total >= 0.9)
    }

    @Test
    fun `rows without a usable embedding are skipped on the binary path`() = runBlocking {
        val withBad = rows(200) + ChunkEmbedding(100_000, FloatArray(0)) + ChunkEmbedding(100_001, FloatArray(10))
        val db = FakeDb(withBad)
        val auto = auto(db, threshold = 50)
        val res = auto.search(vec(3), 5)
        assertEquals(5, res.size)
        assertTrue(res.none { it.id >= 100_000 })
        assertEquals(AutoVectorIndex.Path.BINARY_RESCORE, auto.status().path)
    }

    @Test
    fun `a changed version rebuilds the binary index`() = runBlocking {
        val db = FakeDb(rows(200))
        var version = 1L
        val auto = auto(db, threshold = 50, version = { version })
        auto.search(vec(1), 5)
        auto.search(vec(2), 5)
        assertEquals(1, auto.binaryBuildCount)
        db.rows = rows(210)
        version = 2L
        auto.search(vec(3), 5)
        assertEquals(2, auto.binaryBuildCount)
    }

    @Test
    fun `a corpus that shrinks below the threshold goes back to the exact path`() = runBlocking {
        val db = FakeDb(rows(200))
        val auto = auto(db, threshold = 100)
        auto.search(vec(1), 5)
        assertEquals(AutoVectorIndex.Path.BINARY_RESCORE, auto.status().path)
        db.rows = rows(60)
        auto.search(vec(1), 5)
        assertEquals(AutoVectorIndex.Path.EXACT_MEMORY, auto.status().path)
    }

    @Test
    fun `when even the binary structure would not fit the database scan answers`() = runBlocking {
        val db = FakeDb(rows(200))
        val auto = auto(db, threshold = 50, maxBytes = 1_000L)
        val exact = BruteForceVectorIndex(db.dao)
        assertEquals(exact.search(vec(4), 10).map { it.id }, auto.search(vec(4), 10).map { it.id })
        assertEquals(AutoVectorIndex.Path.DATABASE_SCAN, auto.status().path)
        assertEquals(0, auto.binaryBuildCount)
    }

    @Test
    fun `degenerate queries return nothing and warm-up works on both paths`() = runBlocking {
        val small = auto(FakeDb(rows(20)), threshold = 100)
        val large = auto(FakeDb(rows(200)), threshold = 100)
        assertTrue(small.search(vec(1), 0).isEmpty())
        assertTrue(small.search(FloatArray(10), 5).isEmpty())
        assertTrue(IndexWarmup(small, startDelayMs = 0L).run())
        assertTrue(IndexWarmup(large, startDelayMs = 0L).run())
        assertEquals(AutoVectorIndex.Path.BINARY_RESCORE, large.status().path)
        assertEquals(1, large.binaryBuildCount)   // the warm-up already built it
        assertNotEquals(AutoVectorIndex.Path.NONE, small.status().path)
    }

    @Test
    fun `index generation bumps and is monotone`() {
        val g = IndexGeneration()
        assertEquals(0L, g.current)
        assertEquals(1L, g.bump())
        assertEquals(2L, g.bump())
        assertEquals(2L, g.current)
    }
}
