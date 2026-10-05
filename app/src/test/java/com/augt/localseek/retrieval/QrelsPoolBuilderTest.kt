package com.augt.localseek.retrieval

import com.augt.localseek.data.BenchmarkRunEntity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrelsPoolBuilderTest {

    private fun createMockRun(
        backend: String,
        resultIds: List<String>,
        titles: List<String> = emptyList(),
        snippets: List<String> = emptyList(),
        types: List<String> = emptyList()
    ): BenchmarkRunEntity {
        return BenchmarkRunEntity(
            runSessionId = "session1",
            queryId = "query1",
            queryText = "test query",
            timestamp = 1000L,
            deviceModel = "Pixel 6",
            androidVersion = "13",
            backend = backend,
            corpusSizeChunks = 100,
            corpusSizeApps = 10,
            corpusSizeContacts = 10,
            latencyBm25Ms = 100L,
            latencyDenseMs = 100L,
            latencyFusionMs = 50L,
            latencyRerankMs = 0L,
            latencyTotalMs = 250L,
            memoryMbPeak = 50f,
            batteryPctBefore = 90,
            batteryPctAfter = 89,
            resultIdsJson = JSONArray(resultIds).toString(),
            resultScoresJson = JSONArray(List(resultIds.size) { 0.9 - it * 0.05 }).toString(),
            resultEntityTypesJson = JSONArray(if (types.isEmpty()) List(resultIds.size) { "FILE" } else types).toString(),
            resultTitlesJson = JSONArray(if (titles.isEmpty()) List(resultIds.size) { "Title for ${resultIds[it]}" } else titles).toString(),
            resultSnippetsJson = JSONArray(if (snippets.isEmpty()) List(resultIds.size) { "Snippet for ${resultIds[it]}" } else snippets).toString()
        )
    }

    @Test
    fun `buildPool should deduplicate items appearing in multiple backends`() {
        val run1 = createMockRun("bm25", listOf("FILE:1", "FILE:2", "FILE:3"))
        val run2 = createMockRun("dense", listOf("FILE:2", "FILE:3", "FILE:4"))

        val pool = QrelsPoolBuilder.buildPool(listOf(run1, run2))

        // Union of {1, 2, 3} and {2, 3, 4} should be {1, 2, 3, 4}
        assertEquals(4, pool.size)
        val ids = pool.map { it.resultId }.toSet()
        assertTrue(ids.contains("FILE:1"))
        assertTrue(ids.contains("FILE:2"))
        assertTrue(ids.contains("FILE:3"))
        assertTrue(ids.contains("FILE:4"))
    }

    @Test
    fun `buildPool should include items appearing in only one backend`() {
        val run1 = createMockRun("bm25", listOf("FILE:1"))
        val run2 = createMockRun("dense_bruteforce", listOf("CONTACT:99"))

        val pool = QrelsPoolBuilder.buildPool(listOf(run1, run2))

        assertEquals(2, pool.size)
        val ids = pool.map { it.resultId }.toSet()
        assertTrue(ids.contains("FILE:1"))
        assertTrue(ids.contains("CONTACT:99"))
    }

    @Test
    fun `buildPool should shuffle results and avoid rank bias`() {
        val ids = (1..20).map { "FILE:$it" }
        val run = createMockRun("bm25", ids)

        // Run twice with different seeds
        val pool1 = QrelsPoolBuilder.buildPool(listOf(run), seed = 1234L)
        val pool2 = QrelsPoolBuilder.buildPool(listOf(run), seed = 5678L)

        // pool1 and pool2 should have same items but different order
        assertEquals(15, pool1.size) // Capped at top-15
        assertEquals(15, pool2.size)
        assertEquals(pool1.map { it.resultId }.toSet(), pool2.map { it.resultId }.toSet())
        assertNotEquals(pool1.map { it.resultId }, pool2.map { it.resultId })
        
        // pool1 should not be in original rank order
        assertNotEquals(ids.take(15), pool1.map { it.resultId })
    }

    @Test
    fun `buildPool should respect top-N limit per backend`() {
        val ids = (1..30).map { "FILE:$it" }
        val run = createMockRun("bm25", ids)

        val pool = QrelsPoolBuilder.buildPool(listOf(run))

        assertEquals(15, pool.size) // Constrained by POOL_SIZE_PER_BACKEND = 15
    }

    @Test
    fun `buildPool should handle multiple backends with overlap correctly`() {
        val runs = listOf(
            createMockRun("b1", (1..10).map { "ID:$it" }),
            createMockRun("b2", (5..15).map { "ID:$it" }),
            createMockRun("b3", (10..20).map { "ID:$it" })
        )

        val pool = QrelsPoolBuilder.buildPool(runs)
        
        // Union of 1-10, 5-15, 10-20 is 1-20
        assertEquals(20, pool.size)
        assertTrue(pool.any { it.resultId == "ID:1" })
        assertTrue(pool.any { it.resultId == "ID:20" })
    }
}
