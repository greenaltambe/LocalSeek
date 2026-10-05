package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FusionCalibrationTest {

    private val ranker = FusionRanker()
    private val now = 1700000000000L

    private fun candidate(
        id: Long,
        entityType: EntityType,
        denseScore: Double?,
        bm25Score: Double? = null,
        stableKey: String = "key_$id"
    ) = FusionCandidate(
        id = id,
        title = "Title $id",
        snippet = "Snippet $id",
        filePath = "/path/$id",
        fileType = if (entityType == EntityType.IMAGE) "jpg" else "txt",
        modifiedAt = now,
        sizeBytes = 1000L,
        bm25Score = bm25Score,
        denseScore = denseScore,
        entityType = entityType,
        stableKey = stableKey
    )

    @Test
    fun `RRF is strictly invariant to monotonic score scaling`() {
        val originalCandidates = listOf(
            candidate(1, EntityType.FILE, denseScore = 0.85, bm25Score = 15.2),
            candidate(2, EntityType.APP, denseScore = 0.72, bm25Score = 8.1),
            candidate(3, EntityType.IMAGE, denseScore = 0.31, bm25Score = null),
            candidate(4, EntityType.FILE, denseScore = 0.60, bm25Score = 22.0),
            candidate(5, EntityType.CONTACT, denseScore = 0.78, bm25Score = 5.0)
        )

        // Monotonic transformation: g(s) = exp(s * 1.5) + 10.0
        val transformedCandidates = originalCandidates.map { c ->
            c.copy(
                denseScore = c.denseScore?.let { exp(it * 1.5) + 10.0 },
                bm25Score = c.bm25Score?.let { exp(it * 0.1) + 50.0 }
            )
        }

        val originalRrf = ranker.rankRrf(originalCandidates)
        val transformedRrf = ranker.rankRrf(transformedCandidates)

        assertEquals("Rank count must match", originalRrf.size, transformedRrf.size)

        for (i in originalRrf.indices) {
            val orig = originalRrf[i]
            val trans = transformedRrf[i]
            assertEquals("Item at rank $i ID must match", orig.id, trans.id)
            assertEquals("Item at rank $i entityType must match", orig.entityType, trans.entityType)
            assertEquals("RRF score at rank $i must be identical", orig.finalScore, trans.finalScore, 0.0000001)
        }
    }

    @Test
    fun `perSpaceNorm isolates CLIP cosine space from MiniLM cosine space in global normalization`() {
        // Fixture: CLIP scores in [0.25, 0.35], MiniLM scores in [0.30, 0.90]
        val miniLmCandidates = listOf(
            candidate(1, EntityType.FILE, denseScore = 0.30, bm25Score = 10.0),
            candidate(2, EntityType.FILE, denseScore = 0.60, bm25Score = 10.0),
            candidate(3, EntityType.FILE, denseScore = 0.90, bm25Score = 10.0)
        )
        val clipCandidates = listOf(
            candidate(10, EntityType.IMAGE, denseScore = 0.25, bm25Score = 0.0),
            candidate(11, EntityType.IMAGE, denseScore = 0.30, bm25Score = 0.0),
            candidate(12, EntityType.IMAGE, denseScore = 0.35, bm25Score = 0.0)
        )
        val allCandidates = miniLmCandidates + clipCandidates

        // 1. With perSpaceNorm = false (Legacy cross-space pooling bug)
        val legacyRanked = ranker.rank(
            query = "test",
            results = allCandidates,
            mode = FusionMode.GLOBAL_NORMALIZATION,
            perSpaceNorm = false,
            typePriorEnabled = false
        )
        val topClipLegacy = legacyRanked.first { it.id == 12L }
        val topMiniLmLegacy = legacyRanked.first { it.id == 3L }

        // Under legacy pooling, CLIP max (0.35) is pooled with MiniLM max (0.90),
        // giving the top CLIP candidate a suppressed dense norm of (0.35 - 0.25) / (0.90 - 0.25) = ~0.154
        assertTrue("Legacy pooling heavily suppresses CLIP scores",
            topClipLegacy.finalScore < topMiniLmLegacy.finalScore * 0.5)

        // 2. With perSpaceNorm = true (Clean calibrated normalization)
        val cleanRanked = ranker.rank(
            query = "test",
            results = allCandidates,
            mode = FusionMode.GLOBAL_NORMALIZATION,
            perSpaceNorm = true,
            typePriorEnabled = false
        )
        val topClipClean = cleanRanked.first { it.id == 12L }

        // With perSpaceNorm = true, the top image (0.35) maps to denseNorm = 1.0 within the CLIP space.
        // Its final dense contribution is 0.35 * 1.0 = 0.35
        assertTrue("Calibrated per-space normalization gives top CLIP image full dense weight",
            topClipClean.finalScore >= 0.35)
        assertTrue("Clean score must be significantly higher than legacy suppressed score",
            topClipClean.finalScore > topClipLegacy.finalScore)
    }

    @Test
    fun `typePriorEnabled flag controls whether file type multipliers are applied`() {
        val pdfCandidate = candidate(1, EntityType.FILE, denseScore = 0.8, bm25Score = 5.0).copy(fileType = "pdf")
        val apkCandidate = candidate(2, EntityType.FILE, denseScore = 0.8, bm25Score = 5.0).copy(fileType = "apk")

        // In CLEAN mode (typePriorEnabled = false), both identical-scoring files receive identical finalScore
        val cleanRanked = ranker.rank(
            query = "doc",
            results = listOf(pdfCandidate, apkCandidate),
            mode = FusionMode.GLOBAL_NORMALIZATION,
            typePriorEnabled = false
        )
        assertEquals("PDF and APK scores must be identical when type priors are disabled",
            cleanRanked[0].finalScore, cleanRanked[1].finalScore, 0.0001)

        // In LEGACY mode (typePriorEnabled = true), pdf gets 1.1x boost and apk gets 0.9x penalty
        val legacyRanked = ranker.rank(
            query = "doc",
            results = listOf(pdfCandidate, apkCandidate),
            mode = FusionMode.GLOBAL_NORMALIZATION,
            typePriorEnabled = true
        )
        val legacyPdf = legacyRanked.first { it.fileType == "pdf" }
        val legacyApk = legacyRanked.first { it.fileType == "apk" }
        assertTrue("PDF must score higher than APK when legacy type priors are enabled",
            legacyPdf.finalScore > legacyApk.finalScore)
    }

    @Test
    fun `tie breaking is strictly deterministic by stableKey then id`() {
        val candidates = listOf(
            candidate(2, EntityType.FILE, denseScore = 0.5, bm25Score = 5.0, stableKey = "key_b"),
            candidate(1, EntityType.FILE, denseScore = 0.5, bm25Score = 5.0, stableKey = "key_a"),
            candidate(3, EntityType.FILE, denseScore = 0.5, bm25Score = 5.0, stableKey = "key_c")
        )

        val ranked = ranker.rank(query = "query", results = candidates)
        assertEquals("key_a", ranked[0].stableKey)
        assertEquals("key_b", ranked[1].stableKey)
        assertEquals("key_c", ranked[2].stableKey)
    }
}
