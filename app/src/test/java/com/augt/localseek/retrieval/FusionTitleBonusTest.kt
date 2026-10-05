package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTitleBonusTest {

    private val ranker = FusionRanker()

    private fun candidate(
        id: Long,
        title: String,
        bm25Score: Double? = 10.0,
        denseScore: Double? = 0.5,
        stableKey: String = "key_$id"
    ) = FusionCandidate(
        id = id,
        title = title,
        snippet = "snippet",
        filePath = "/path/$id",
        fileType = "txt",
        modifiedAt = 1_000_000L,
        sizeBytes = 100L,
        bm25Score = bm25Score,
        denseScore = denseScore,
        entityType = EntityType.FILE,
        stableKey = stableKey
    )

    @Test
    fun `linear fusion awards additive 0_10 bonus for title matches`() {
        val uppercaseCandidate = candidate(1, title = "ANNUAL BUDGET 2026")

        val linearWithMatch = ranker.rank("budget", listOf(uppercaseCandidate), FusionMode.GLOBAL_NORMALIZATION)
        val linearNoMatch = ranker.rank("invoice", listOf(uppercaseCandidate), FusionMode.GLOBAL_NORMALIZATION)
        val linearDiff = linearWithMatch.first().finalScore - linearNoMatch.first().finalScore

        assertEquals(0.10, linearDiff, 1e-6)
    }

    @Test
    fun `title-matching candidate outranks non-matching one under RRF when other signals are comparable`() {
        val query = "passport"

        // Candidate A: rank 2 in BM25, no dense, but matches query in title
        val candidateA = candidate(1, title = "My Passport Scan", bm25Score = 10.0, denseScore = null)
        // Candidate B: rank 1 in BM25, no dense, title does not match
        val candidateB = candidate(2, title = "Unrelated Document", bm25Score = 15.0, denseScore = null)

        val ranked = ranker.rank(
            query = query,
            results = listOf(candidateA, candidateB),
            mode = FusionMode.RRF
        )

        assertEquals("Candidate A with title match should be ranked #1", candidateA.id, ranked[0].id)
        assertEquals("Candidate B should be ranked #2", candidateB.id, ranked[1].id)

        // RRF scores:
        // Candidate A: 1/(60+2) [bm25] + 1/(60+1) [title] = 1/62 + 1/61 ≈ 0.032522
        // Candidate B: 1/(60+1) [bm25] = 1/61 ≈ 0.016393
        val scoreA = ranked.first { it.id == candidateA.id }.finalScore
        val scoreB = ranked.first { it.id == candidateB.id }.finalScore
        assertTrue("Score of A should exceed B", scoreA > scoreB)
        assertEquals(1.0 / 62.0 + 1.0 / 61.0, scoreA, 1e-6)
        assertEquals(1.0 / 61.0, scoreB, 1e-6)
    }

    @Test
    fun `title-matching candidate does NOT outrank non-matching one under RRF when other signals have large gap`() {
        val query = "passport"

        // Candidate C: rank 1 in both BM25 and Dense, but does not match title
        val candidateC = candidate(1, title = "Government Identity Doc", bm25Score = 50.0, denseScore = 0.95)
        // Candidate D: rank 20 in BM25, no dense, but matches title
        // Create 18 intermediate candidates so D has BM25 rank 20
        val intermediates = (2..19).map { i ->
            candidate(i.toLong(), title = "Other Doc $i", bm25Score = 30.0 - i, denseScore = null)
        }
        val candidateD = candidate(20, title = "Passport Picture", bm25Score = 1.0, denseScore = null)

        val ranked = ranker.rank(
            query = query,
            results = listOf(candidateC) + intermediates + listOf(candidateD),
            mode = FusionMode.RRF
        )

        // Candidate C: 1/(60+1) [bm25] + 1/(60+1) [dense] = 2/61 ≈ 0.032787
        // Candidate D: 1/(60+20) [bm25] + 1/(60+1) [title] = 1/80 + 1/61 ≈ 0.028893
        // Candidate C must win, unlike the flawed additive +0.10 bonus which would have given D 0.1125 and crushed C.
        val topResult = ranked.first()
        assertEquals("Strong multi-channel candidate C should outrank weak candidate D despite title match", candidateC.id, topResult.id)

        val scoreC = ranked.first { it.id == candidateC.id }.finalScore
        val scoreD = ranked.first { it.id == candidateD.id }.finalScore
        assertTrue("Score C must be greater than score D", scoreC > scoreD)
    }
}
