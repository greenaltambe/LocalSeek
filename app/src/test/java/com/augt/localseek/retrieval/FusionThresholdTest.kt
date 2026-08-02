package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionThresholdTest {

    private val ranker = FusionRanker()

    @Test
    fun `PER_TYPE_WITH_THRESHOLD should exclude entity types below both floors`() {
        val results = listOf(
            // Strong APP match (BM25=20, Dense=0.8) -> Floor BM25 = 10.0
            FusionCandidate(
                id = 1, title = "WhatsApp", snippet = "app", filePath = "com.whatsapp",
                fileType = "app", entityType = EntityType.APP,
                bm25Score = 20.0, denseScore = 0.8, modifiedAt = 1000L, sizeBytes = 0L
            ),
            // Weak FILE match (BM25=1.0, Dense=0.2)
            FusionCandidate(
                id = 101, title = "Random File", snippet = "nothing", filePath = "/a/b.txt",
                fileType = "txt", entityType = EntityType.FILE,
                bm25Score = 1.0, denseScore = 0.2, modifiedAt = 1000L, sizeBytes = 100L
            )
        )

        val ranked = ranker.rank("whatsapp", results, FusionMode.PER_TYPE_WITH_THRESHOLD)
        
        // APP is present
        assertTrue(ranked.any { it.entityType == EntityType.APP })
        // FILE should be excluded because best BM25 (1.0) < 10.0 AND best Dense (0.2) < 0.35
        assertFalse(ranked.any { it.entityType == EntityType.FILE })
    }

    @Test
    fun `PER_TYPE_WITH_THRESHOLD should include type if it clears Dense floor but not BM25`() {
        val results = listOf(
            FusionCandidate(
                id = 1, title = "Top", snippet = "...", filePath = "path",
                fileType = "txt", entityType = EntityType.FILE,
                bm25Score = 10.0, denseScore = 0.5, modifiedAt = 1000L, sizeBytes = 100L
            ),
            FusionCandidate(
                id = 2, title = "DenseOnly", snippet = "...", filePath = "app",
                fileType = "app", entityType = EntityType.APP,
                bm25Score = 1.0, denseScore = 0.4, modifiedAt = 1000L, sizeBytes = 0L
            )
        )
        // BM25 Floor = 5.0. APP (1.0) fails BM25.
        // APP Dense (0.4) clears Dense floor (0.35).
        // APP should be INCLUDED.
        
        val ranked = ranker.rank("query", results, FusionMode.PER_TYPE_WITH_THRESHOLD)
        assertTrue(ranked.any { it.entityType == EntityType.APP })
    }

    @Test
    fun `PER_TYPE_WITH_THRESHOLD should include BM25Only when it is the top overall match`() {
        val results = listOf(
            FusionCandidate(
                id = 1, title = "Bread", snippet = "load of bread", filePath = "/docs/bread.txt",
                fileType = "txt", entityType = EntityType.FILE,
                bm25Score = 15.0, denseScore = 0.2, modifiedAt = 1000L, sizeBytes = 100L
            )
        )
        // Max BM25 = 15.0. Floor = 7.5. FILE (15.0) clears it.
        // FILE Dense (0.2) fails floor (0.35).
        // But since it clears BM25, it should be INCLUDED.
        
        val ranked = ranker.rank("load of bread", results, FusionMode.PER_TYPE_WITH_THRESHOLD)
        assertTrue(ranked.any { it.entityType == EntityType.FILE })
    }
}
