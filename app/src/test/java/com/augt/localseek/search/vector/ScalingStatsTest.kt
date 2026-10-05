package com.augt.localseek.search.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalingStatsTest {
    @Test
    fun `nearest rank percentiles`() {
        val v = LongArray(100) { (100 - it).toLong() } // unsorted 100..1
        assertEquals(50L, ScalingStats.percentile(v, 50.0))
        assertEquals(95L, ScalingStats.percentile(v, 95.0))
        assertEquals(99L, ScalingStats.percentile(v, 99.0))
        assertEquals(1L, ScalingStats.percentile(v, 0.0))
        assertEquals(100L, ScalingStats.percentile(v, 100.0))
        assertEquals(7L, ScalingStats.percentile(longArrayOf(7), 99.0))
        assertEquals(0L, ScalingStats.percentile(LongArray(0), 50.0))
    }

    @Test
    fun `recall at k`() {
        assertEquals(0.5, ScalingStats.recallAtK(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 9, 8), 4), 1e-12)
        assertEquals(1.0, ScalingStats.recallAtK(intArrayOf(5, 6), intArrayOf(6, 5, 1, 2), 2), 1e-12)
        assertEquals(0.0, ScalingStats.recallAtK(intArrayOf(), intArrayOf(1, 2), 2), 1e-12)
        assertEquals(1.0, ScalingStats.recallAtK(intArrayOf(1), intArrayOf(), 10), 1e-12)
        // only the first k of each list count
        assertEquals(0.5, ScalingStats.recallAtK(intArrayOf(1, 7, 2), intArrayOf(1, 2, 3), 2), 1e-12)
    }

    @Test
    fun `median pass index`() {
        assertEquals(2, ScalingStats.medianPassIndex(longArrayOf(30, 10, 20)))
        assertEquals(1, ScalingStats.medianPassIndex(longArrayOf(5, 5, 5))) // ties are ordered by pass index
        assertEquals(-1, ScalingStats.medianPassIndex(LongArray(0)))
        assertEquals(0, ScalingStats.medianPassIndex(longArrayOf(9)))
    }

    @Test
    fun `heap budget rule is 70 percent of the maximum`() {
        val max = 384L * 1024 * 1024
        assertFalse(ScalingStats.exceedsHeapBudget(50_000_000, 100_000_000, max))
        assertTrue(ScalingStats.exceedsHeapBudget(50_000_000, 300_000_000, max))
        assertTrue(ScalingStats.exceedsHeapBudget(0, (0.7 * max).toLong() + 1, max))
        assertFalse(ScalingStats.exceedsHeapBudget(0, (0.7 * max).toLong(), max))
    }
}
