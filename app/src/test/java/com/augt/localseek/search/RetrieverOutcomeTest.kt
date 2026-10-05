package com.augt.localseek.search

import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrieverOutcomeTest {

    private fun sampleCandidate(id: Long) = SearchResult(
        id = id,
        title = "Test $id",
        snippet = "Snippet $id",
        filePath = "/path/$id",
        fileType = "txt",
        score = 0.9f,
        modifiedAt = 1000L,
        sizeBytes = 200L,
        entityType = EntityType.FILE
    )

    @Test
    fun `Ran with empty list is strictly distinct from Skipped or Failed`() {
        val ranEmpty: RetrieverOutcome = RetrieverOutcome.Ran(emptyList(), 12L)
        val skipped: RetrieverOutcome = RetrieverOutcome.Skipped("Disabled by config")
        val failed: RetrieverOutcome = RetrieverOutcome.Failed("Index not initialized")

        assertTrue(ranEmpty is RetrieverOutcome.Ran)
        assertTrue(skipped is RetrieverOutcome.Skipped)
        assertTrue(failed is RetrieverOutcome.Failed)

        assertNotEquals(ranEmpty, skipped)
        assertNotEquals(ranEmpty, failed)
        assertNotEquals(skipped, failed)

        assertEquals(0, (ranEmpty as RetrieverOutcome.Ran).candidates.size)
        assertEquals(12L, ranEmpty.latencyMs)
        assertEquals("Disabled by config", (skipped as RetrieverOutcome.Skipped).reason)
        assertEquals("Index not initialized", (failed as RetrieverOutcome.Failed).cause)
    }

    @Test
    fun `Ran preserves candidate order and latency`() {
        val c1 = sampleCandidate(1)
        val c2 = sampleCandidate(2)
        val outcome = RetrieverOutcome.Ran(listOf(c1, c2), 45L)

        assertEquals(2, outcome.candidates.size)
        assertEquals(1L, outcome.candidates[0].id)
        assertEquals(2L, outcome.candidates[1].id)
        assertEquals(45L, outcome.latencyMs)
    }

    @Test
    fun `exhaustive pattern matching over RetrieverOutcome`() {
        val outcomes: List<RetrieverOutcome> = listOf(
            RetrieverOutcome.Ran(listOf(sampleCandidate(1)), 10L),
            RetrieverOutcome.Skipped("reason"),
            RetrieverOutcome.Failed("cause")
        )

        val labels = outcomes.map { outcome ->
            when (outcome) {
                is RetrieverOutcome.Ran -> "ran:${outcome.candidates.size}"
                is RetrieverOutcome.Skipped -> "skipped:${outcome.reason}"
                is RetrieverOutcome.Failed -> "failed:${outcome.cause}"
            }
        }

        assertEquals(listOf("ran:1", "skipped:reason", "failed:cause"), labels)
    }
}
