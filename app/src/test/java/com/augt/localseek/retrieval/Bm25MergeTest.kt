package com.augt.localseek.retrieval

import com.augt.localseek.search.query.QueryProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25MergeTest {

    @Test
    fun `best hit of every table scores 1 and ties go to files then apps then contacts`() {
        val merged = Bm25Merge.rrfAcrossTables(listOf(listOf("f1", "f2"), listOf("a1"), listOf("c1")), limit = 10)
        assertEquals(listOf("f1", "a1", "c1", "f2"), merged.map { it.first })
        assertEquals(1f, merged[0].second, 0f)
        assertEquals(1f, merged[1].second, 0f)
        assertEquals(61f / 62f, merged[3].second, 1e-6f)
    }

    @Test
    fun `scores depend only on rank so a huge raw score in one table cannot dominate`() {
        // three apps ranked ahead of the second file: rank 1 of apps ties file rank 1, ranks 2 and 3 do not beat file rank 2 only if group order says so
        val merged = Bm25Merge.rrfAcrossTables(listOf(listOf("f1", "f2", "f3"), listOf("a1", "a2", "a3")), limit = 10)
        assertEquals(listOf("f1", "a1", "f2", "a2", "f3", "a3"), merged.map { it.first })
    }

    @Test
    fun `limit, empty groups and monotonic scores`() {
        assertTrue(Bm25Merge.rrfAcrossTables(listOf(emptyList<String>(), emptyList()), 5).isEmpty())
        val m = Bm25Merge.rrfAcrossTables(listOf((1..30).map { "f$it" }, (1..30).map { "a$it" }), limit = 20)
        assertEquals(20, m.size)
        assertEquals(m.map { it.second }.sortedDescending(), m.map { it.second })
        assertTrue(m.all { it.second > 0f && it.second <= 1f })
    }

    @Test
    fun `raw dense query is trimmed, lower-cased and whitespace-collapsed only`() {
        assertEquals("what is the tax   due".replace(Regex("\\s+"), " "), QueryProcessor.rawDenseQuery("  What is the  TAX \t due\n"))
        assertEquals("the of a", QueryProcessor.rawDenseQuery("The OF  a"))   // stop words kept
        assertEquals("", QueryProcessor.rawDenseQuery("   "))
    }
}
