package com.augt.localseek.eval

import com.github.jelmerk.hnswlib.core.DistanceFunctions
import com.github.jelmerk.hnswlib.core.Item
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Does hnswlib-core 1.2.1 work on Android? 5,000 seeded unit vectors with cluster structure (40 centres, within-cluster cosine about
 * 0.5, like related text chunks), recall@10 vs brute force at efSearch 128 must be >= 0.95. On structureless Gaussian vectors
 * (all pairs nearly orthogonal) the same index reaches only about 0.80; that number is logged, not asserted.
 */
class HnswLibSmokeInstrumentedTest {
    private class V(private val i: Int, private val v: FloatArray) : Item<Int, FloatArray> {
        override fun id() = i
        override fun vector() = v
        override fun dimensions() = v.size
    }

    private fun unit(rnd: Random) = FloatArray(384) { rnd.nextGaussian().toFloat() }.let { v -> val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(384) { v[it] / n } }

    private fun recall(data: List<FloatArray>, queries: List<FloatArray>): Double {
        val idx: HnswIndex<Int, FloatArray, V, Float> = HnswIndex.newBuilder(384, DistanceFunctions.FLOAT_COSINE_DISTANCE, data.size)
            .withM(16).withEfConstruction(200).withEf(128).build()
        idx.addAll(data.mapIndexed { i, v -> V(i, v) })
        var hit = 0
        for (q in queries) {
            val truth = data.indices.sortedByDescending { r -> var d = 0f; for (i in 0 until 384) d += q[i] * data[r][i]; d }.take(10).toSet()
            hit += idx.findNearest(q, 10).count { it.item().id() in truth }
        }
        return hit / (queries.size * 10.0)
    }

    @Test
    fun recallAtEf128IsAtLeast095() {
        val centres = Random(1234).let { r -> List(40) { unit(r) } }
        val rnd = Random(1)
        fun clustered(): FloatArray { val c = centres[rnd.nextInt(40)]; val g = unit(rnd); val v = FloatArray(384) { c[it] + g[it] }; val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(384) { v[it] / n } }
        val data = List(5000) { clustered() }
        val recall = recall(data, List(100) { clustered() })
        val r2 = Random(2)
        val gauss = List(5000) { unit(r2) }
        val gaussRecall = recall(gauss, List(100) { unit(r2) })
        android.util.Log.i("SCALING", "SCALING hnsw smoke recall@10 clustered = $recall, structureless gaussian = $gaussRecall (ef=128, 5000 vectors)")
        assertTrue("recall $recall", recall >= 0.95)
    }
}
