package com.augt.localseek

import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionRanker
import com.augt.localseek.retrieval.ScoreNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4FusionTest {

    @Test
    fun minMaxNorm_mapsValuesToZeroOneRange() {
        val normalized = ScoreNormalizer.minMaxNorm(listOf(2.0, 4.0, 6.0))
        assertEquals(0.0, normalized[0], 0.0001)
        assertEquals(0.5, normalized[1], 0.0001)
        assertEquals(1.0, normalized[2], 0.0001)
    }

    @Test
    fun rank_boostsTitleMatches() {
        val ranker = FusionRanker()
        val now = System.currentTimeMillis()

        val results = listOf(
            FusionCandidate(
                id = 1,
                title = "Kotlin Guide",
                snippet = "intro",
                filePath = "/a",
                fileType = "txt",
                modifiedAt = now,
                sizeBytes = 1000,
                bm25Score = 0.8,
                denseScore = 0.6
            ),
            FusionCandidate(
                id = 2,
                title = "Random Notes",
                snippet = "kotlin",
                filePath = "/b",
                fileType = "txt",
                modifiedAt = now,
                sizeBytes = 1000,
                bm25Score = 0.8,
                denseScore = 0.6
            )
        )

        val ranked = ranker.rank(query = "kotlin", results = results)
        assertEquals(1L, ranked.first().id)
        assertTrue(ranked.first().finalScore >= ranked.last().finalScore)
    }
}

