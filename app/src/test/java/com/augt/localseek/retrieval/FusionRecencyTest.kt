package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FusionRecencyTest {

    private val ranker = FusionRanker()
    private val refTime = 1_700_000_000_000L

    private fun candidate(
        id: Long,
        entityType: EntityType,
        modifiedAt: Long,
        bm25Score: Double = 10.0,
        denseScore: Double = 0.5
    ) = FusionCandidate(
        id = id,
        title = "Item $id",
        snippet = "snippet",
        filePath = "/path/$id",
        fileType = if (entityType == EntityType.IMAGE) "jpg" else "txt",
        modifiedAt = modifiedAt,
        sizeBytes = 100L,
        bm25Score = bm25Score,
        denseScore = denseScore,
        entityType = entityType,
        stableKey = "key_$id"
    )

    @Test
    fun `APP and CONTACT receive zero recency contribution in global normalization`() {
        val appRecent = candidate(1, EntityType.APP, modifiedAt = refTime)
        val appOld = candidate(2, EntityType.APP, modifiedAt = refTime - 100L * 86_400_000L)

        val contactRecent = candidate(3, EntityType.CONTACT, modifiedAt = refTime)
        val contactOld = candidate(4, EntityType.CONTACT, modifiedAt = refTime - 100L * 86_400_000L)

        val ranked = ranker.rank(
            query = "test",
            results = listOf(appRecent, appOld, contactRecent, contactOld),
            mode = FusionMode.GLOBAL_NORMALIZATION,
            referenceTime = refTime
        )

        val appRecentRanked = ranked.first { it.id == 1L }
        val appOldRanked = ranked.first { it.id == 2L }
        val contactRecentRanked = ranked.first { it.id == 3L }
        val contactOldRanked = ranked.first { it.id == 4L }

        assertEquals(
            "APP with recent index time should not receive recency boost over older index time",
            appRecentRanked.finalScore,
            appOldRanked.finalScore,
            1e-6
        )
        assertEquals(
            "CONTACT with recent index time should not receive recency boost over older index time",
            contactRecentRanked.finalScore,
            contactOldRanked.finalScore,
            1e-6
        )
    }

    @Test
    fun `APP and CONTACT receive zero recency contribution in per-type normalization`() {
        val appRecent = candidate(1, EntityType.APP, modifiedAt = refTime)
        val appOld = candidate(2, EntityType.APP, modifiedAt = refTime - 30L * 86_400_000L)

        val ranked = ranker.rank(
            query = "test",
            results = listOf(appRecent, appOld),
            mode = FusionMode.PER_TYPE_NORMALIZATION,
            referenceTime = refTime
        )

        assertEquals(
            "Per-type normalization must not boost apps based on index time",
            ranked[0].finalScore,
            ranked[1].finalScore,
            1e-6
        )
    }

    @Test
    fun `FILE and IMAGE receive recency contribution based on genuine modification time`() {
        val fileRecent = candidate(1, EntityType.FILE, modifiedAt = refTime)
        val fileOld = candidate(2, EntityType.FILE, modifiedAt = refTime - 60L * 86_400_000L)

        val ranked = ranker.rank(
            query = "test",
            results = listOf(fileRecent, fileOld),
            mode = FusionMode.GLOBAL_NORMALIZATION,
            referenceTime = refTime
        )

        val rankedRecent = ranked.first { it.id == 1L }
        val rankedOld = ranked.first { it.id == 2L }

        assertTrue(
            "Recently modified file should score higher than older file",
            rankedRecent.finalScore > rankedOld.finalScore
        )
    }

    @Test
    fun `calculateRecency produces deterministic values with reference timestamp`() {
        val scoreNow = ranker.calculateRecency(timestamp = refTime, referenceTime = refTime)
        assertEquals(1.0, scoreNow, 1e-6)

        val score30DaysAgo = ranker.calculateRecency(
            timestamp = refTime - 30L * 86_400_000L,
            referenceTime = refTime
        )
        assertEquals(exp(-1.0), score30DaysAgo, 1e-6)
    }
}
