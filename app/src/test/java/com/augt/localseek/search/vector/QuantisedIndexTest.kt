package com.augt.localseek.search.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class QuantisedIndexTest {
    private val dim = 384

    private fun randomUnit(rnd: Random): FloatArray {
        val v = FloatArray(dim) { rnd.nextGaussian().toFloat() }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    private class Data(val ids: LongArray, val flat: FloatArray)

    private fun dataset(n: Int, seed: Long): Data {
        val rnd = Random(seed)
        val flat = FloatArray(n * dim)
        for (r in 0 until n) randomUnit(rnd).copyInto(flat, r * dim)
        return Data(LongArray(n) { it.toLong() + 1000 }, flat)
    }

    /** Float32 exact top-k by dot product, (score desc, id asc). */
    private fun exact(d: Data, q: FloatArray, k: Int): List<Long> =
        d.ids.indices.map { r ->
            var dot = 0f
            for (i in 0 until dim) dot += q[i] * d.flat[r * dim + i]
            d.ids[r] to dot
        }.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first }).take(k).map { it.first }

    private fun recall(d: Data, queries: List<FloatArray>, k: Int, search: (FloatArray) -> List<ScoredResult>): Double {
        var hit = 0
        for (q in queries) hit += search(q).map { it.id }.toSet().intersect(exact(d, q, k).toSet()).size
        return hit.toDouble() / (queries.size * k)
    }

    /**
     * Seeded random unit vectors with cluster structure: 40 random unit centres; every vector is normalise(centre + g) with g a
     * random unit vector, so vectors in one cluster have cosine about 0.5 (like related text chunks; unlike structureless noise,
     * where all pairs are almost orthogonal and sign bits carry little information).
     */
    private fun clustered(n: Int, seed: Long, clusters: Int = 40): List<FloatArray> {
        val rnd = Random(seed)
        val centres = Random(1234).let { r -> List(clusters) { randomUnit(r) } }
        return List(n) {
            val c = centres[rnd.nextInt(clusters)]
            val g = randomUnit(rnd)
            val v = FloatArray(dim) { i -> c[i] + g[i] }
            val norm = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(dim) { i -> v[i] / norm }
        }
    }

    // 2,000 clustered unit vectors; queries are 100 further vectors from the same distribution.
    private val data = Data(LongArray(2_000) { it.toLong() + 1000 }, clustered(2_000, 42).let { l ->
        FloatArray(2_000 * dim).also { f -> l.forEachIndexed { r, v -> v.copyInto(f, r * dim) } }
    })
    private val queries = clustered(100, 7)

    @Test
    fun `structureless gaussian vectors are reported but not asserted`() {
        // Documented limit: on pure noise (all pairs nearly orthogonal) binary recall@10 at k' = 100 is about 0.58.
        val noise = dataset(2_000, 42)
        val qs = Random(7).let { r -> List(100) { randomUnit(r) } }
        val idx = BinaryRescoreIndex(dim, 100).also { it.build(noise.ids, noise.flat) }
        println("binary recall@10 on structureless gaussian vectors = ${recall(noise, qs, 10) { idx.search(it, 10) }}")
    }

    @Test
    fun `int8 recall at 10 is at least 0_95`() {
        val idx = Int8ExactIndex(dim).also { it.build(data.ids, data.flat) }
        val r = recall(data, queries, 10) { idx.search(it, 10) }
        println("int8 recall@10 = $r")
        assertTrue("recall $r", r >= 0.95)
    }

    @Test
    fun `binary with k prime 100 recall at 10 is at least 0_90`() {
        val idx = BinaryRescoreIndex(dim, 100).also { it.build(data.ids, data.flat) }
        val r = recall(data, queries, 10) { idx.search(it, 10) }
        println("binary recall@10 = $r")
        assertTrue("recall $r", r >= 0.90)
    }

    @Test
    fun `binary with k prime equal to the index size is exact`() {
        val small = dataset(300, 5)
        val idx = BinaryRescoreIndex(dim, 300).also { it.build(small.ids, small.flat) }
        queries.take(10).forEach { q -> assertEquals(exact(small, q, 10), idx.search(q, 10).map { it.id }) }
    }

    @Test
    fun `scores are close to the float dot product`() {
        val idx = Int8ExactIndex(dim).also { it.build(data.ids, data.flat) }
        val q = queries[0]
        val top = idx.search(q, 5)
        for (s in top) {
            val row = (s.id - 1000).toInt()
            var dot = 0f
            for (i in 0 until dim) dot += q[i] * data.flat[row * dim + i]
            assertEquals(dot.toDouble(), s.score.toDouble(), 0.01)
        }
        assertEquals(top.sortedByDescending { it.score }.map { it.id }, top.map { it.id })
    }

    @Test
    fun `empty index returns nothing`() {
        val a = Int8ExactIndex(dim)
        val b = BinaryRescoreIndex(dim)
        assertTrue(a.search(queries[0], 10).isEmpty())
        assertTrue(b.search(queries[0], 10).isEmpty())
        a.build(LongArray(0), FloatArray(0)); b.build(LongArray(0), FloatArray(0))
        assertTrue(a.search(queries[0], 10).isEmpty())
        assertTrue(b.search(queries[0], 10).isEmpty())
    }

    @Test
    fun `k larger than n returns all n and k zero returns nothing`() {
        val small = dataset(7, 1)
        val a = Int8ExactIndex(dim).also { it.build(small.ids, small.flat) }
        val b = BinaryRescoreIndex(dim, 100).also { it.build(small.ids, small.flat) }
        assertEquals(7, a.search(queries[0], 50).size)
        assertEquals(7, b.search(queries[0], 50).size)
        assertEquals(7, a.search(queries[0], 50).map { it.id }.toSet().size)
        assertTrue(a.search(queries[0], 0).isEmpty())
        assertTrue(b.search(queries[0], 0).isEmpty())
    }

    @Test
    fun `zero query vector and zero stored vector do not crash and score zero`() {
        val flat = data.flat.copyOf(3 * dim).also { java.util.Arrays.fill(it, dim, 2 * dim, 0f) } // row 1 is all zero
        val ids = longArrayOf(10, 11, 12)
        val a = Int8ExactIndex(dim).also { it.build(ids, flat) }
        val b = BinaryRescoreIndex(dim, 100).also { it.build(ids, flat) }
        val zero = FloatArray(dim)
        assertTrue(a.search(zero, 3).all { it.score == 0f })
        assertTrue(b.search(zero, 3).all { it.score == 0f })
        assertEquals(0f, a.search(flat.copyOfRange(0, dim), 3).first { it.id == 11L }.score, 0f)
        assertEquals(10L, a.search(flat.copyOfRange(0, dim), 1).single().id)
    }

    @Test
    fun `memory accounting`() {
        val a = Int8ExactIndex(dim).also { it.build(data.ids, data.flat) }
        val b = BinaryRescoreIndex(dim).also { it.build(data.ids, data.flat) }
        assertEquals(2000L * (dim + 4 + 8), a.memoryBytes)
        assertEquals(2000L * (6 * 8 + 8), b.memoryBytes)
    }

    @Test
    fun `streaming build gives the same results as the array build`() {
        val a1 = Int8ExactIndex(dim).also { it.build(data.ids, data.flat) }
        val a2 = Int8ExactIndex(dim).also { idx -> idx.buildStreaming(data.ids) { row, dst -> System.arraycopy(data.flat, row * dim, dst, 0, dim) } }
        val b1 = BinaryRescoreIndex(dim, 100).also { it.build(data.ids, data.flat) }
        val b2 = BinaryRescoreIndex(dim, 100).also { idx -> idx.buildStreaming(data.ids) { row, dst -> System.arraycopy(data.flat, row * dim, dst, 0, dim) } }
        for (q in queries.take(20)) {
            assertEquals(a1.search(q, 10), a2.search(q, 10))
            assertEquals(b1.search(q, 10), b2.search(q, 10))
        }
        assertEquals(a1.memoryBytes, a2.memoryBytes)
        assertEquals(b1.memoryBytes, b2.memoryBytes)
    }
}
