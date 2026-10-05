package com.augt.localseek.search.vector

/** Pure helpers of the scaling microbenchmark (Part AN): percentiles, recall, the median pass and the heap-fit rule. */
object ScalingStats {
    /** Nearest-rank percentile (p in 0..100) of [values]; 0 for an empty array. */
    fun percentile(values: LongArray, p: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sortedArray()
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Fraction of the first [k] ground-truth ids found among the first [k] returned ids (both lists may be shorter). */
    fun recallAtK(returned: IntArray, truth: IntArray, k: Int): Double {
        val t = truth.take(k).toSet()
        if (t.isEmpty()) return 1.0
        return returned.take(k).count { it in t }.toDouble() / t.size
    }

    /** Index of the pass with the median value of [p50s] (for 3 passes: the middle one; equal values are ordered by pass index). */
    fun medianPassIndex(p50s: LongArray): Int {
        if (p50s.isEmpty()) return -1
        val order = p50s.indices.sortedWith(compareBy({ p50s[it] }, { it }))
        return order[(order.size - 1) / 2]
    }

    /** True when holding [analyticBytes] more on top of [usedHeapBytes] would exceed [fraction] of [maxHeapBytes]. */
    fun exceedsHeapBudget(usedHeapBytes: Long, analyticBytes: Long, maxHeapBytes: Long, fraction: Double = 0.70): Boolean =
        usedHeapBytes + analyticBytes > fraction * maxHeapBytes
}
