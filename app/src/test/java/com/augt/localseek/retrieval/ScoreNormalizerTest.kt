package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreNormalizerTest {

    @Test
    fun `minMaxNorm returns emptyList for empty input`() {
        val normalized = ScoreNormalizer.minMaxNorm(emptyList())
        assertTrue(normalized.isEmpty())
    }

    @Test
    fun `minMaxNorm returns 0_5 for identical scores`() {
        val scores = listOf(4.2, 4.2, 4.2)
        val normalized = ScoreNormalizer.minMaxNorm(scores)
        assertEquals(3, normalized.size)
        normalized.forEach { assertEquals(0.5, it, 1e-6) }
    }

    @Test
    fun `minMaxNorm returns 0_5 for tiny gaps below EPSILON`() {
        // Gaps smaller than 1e-9 must not be stretched to [0, 1]
        val base = 100.0
        val scores = listOf(base, base + 1e-11, base + 5e-10)
        val normalized = ScoreNormalizer.minMaxNorm(scores)
        assertEquals(3, normalized.size)
        normalized.forEach { assertEquals(0.5, it, 1e-6) }
    }

    @Test
    fun `minMaxNorm scales properly when range exceeds EPSILON`() {
        val scores = listOf(10.0, 15.0, 20.0)
        val normalized = ScoreNormalizer.minMaxNorm(scores)
        assertEquals(0.0, normalized[0], 1e-6)
        assertEquals(0.5, normalized[1], 1e-6)
        assertEquals(1.0, normalized[2], 1e-6)
    }

    @Test
    fun `minMaxNormPerGroup guards against sub-epsilon variance per entity type`() {
        val candidate1 = FusionCandidate(
            id = 1L, title = "App 1", snippet = "", filePath = "", fileType = "app",
            modifiedAt = 0L, sizeBytes = 0L, bm25Score = 1.0, entityType = EntityType.APP
        )
        val candidate2 = FusionCandidate(
            id = 2L, title = "App 2", snippet = "", filePath = "", fileType = "app",
            modifiedAt = 0L, sizeBytes = 0L, bm25Score = 1.0 + 1e-10, entityType = EntityType.APP
        )
        val candidate3 = FusionCandidate(
            id = 3L, title = "File 1", snippet = "", filePath = "", fileType = "txt",
            modifiedAt = 0L, sizeBytes = 0L, bm25Score = 0.0, entityType = EntityType.FILE
        )
        val candidate4 = FusionCandidate(
            id = 4L, title = "File 2", snippet = "", filePath = "", fileType = "txt",
            modifiedAt = 0L, sizeBytes = 0L, bm25Score = 10.0, entityType = EntityType.FILE
        )

        val normMap = ScoreNormalizer.minMaxNormPerGroup(
            listOf(candidate1, candidate2, candidate3, candidate4),
            { it.bm25Score ?: 0.0 },
            { it.entityType }
        )

        // APP group has sub-epsilon range -> neutral 0.5
        assertEquals(0.5, normMap[Pair(EntityType.APP, 1L)] ?: 0.0, 1e-6)
        assertEquals(0.5, normMap[Pair(EntityType.APP, 2L)] ?: 0.0, 1e-6)

        // FILE group has range 10.0 -> normalized to [0, 1]
        assertEquals(0.0, normMap[Pair(EntityType.FILE, 3L)] ?: 0.0, 1e-6)
        assertEquals(1.0, normMap[Pair(EntityType.FILE, 4L)] ?: 0.0, 1e-6)
    }
}
