package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalibrationTest {

    @Test
    fun hypothesis_globalNormalizationCausesSkew() {
        val now = System.currentTimeMillis()
        // File chunks with high raw scores (e.g. from long text matches)
        val fileCandidates = (1..3).map { id ->
            FusionCandidate(
                id = id.toLong(), title = "File $id", snippet = "snippet", filePath = "/path/$id", fileType = "txt",
                modifiedAt = now, sizeBytes = 1000, bm25Score = 10.0 + id, denseScore = 0.8 + (id * 0.05),
                entityType = EntityType.FILE
            )
        }
        // Apps with much lower raw scores (e.g. from short synthetic text)
        val appCandidates = (4..6).map { id ->
            FusionCandidate(
                id = id.toLong(), title = "App $id", snippet = "snippet", filePath = "pkg.$id", fileType = "app",
                modifiedAt = now, sizeBytes = 0, bm25Score = 1.0 + id, denseScore = 0.4 + (id * 0.01),
                entityType = EntityType.APP
            )
        }

        val all = fileCandidates + appCandidates
        val ranker = FusionRanker()

        // GLOBAL_NORMALIZATION
        val globalRanked = ranker.rank("query", all, FusionMode.GLOBAL_NORMALIZATION)
        
        // Hypothesis: Apps will be at the bottom because their raw scores are low compared to files
        val top3EntityTypes = globalRanked.take(3).map { it.entityType }
        assertTrue("Global normalization should be dominated by files in this scenario", 
            top3EntityTypes.all { it == EntityType.FILE })
        
        val appScoreGlobal = globalRanked.find { it.entityType == EntityType.APP }?.finalScore ?: 0.0
        val fileScoreGlobal = globalRanked.find { it.entityType == EntityType.FILE }?.finalScore ?: 0.0
        assertTrue("File score ($fileScoreGlobal) should be significantly higher than app score ($appScoreGlobal) under global norm", 
            fileScoreGlobal > appScoreGlobal * 1.5)
    }

    @Test
    fun perTypeNormalization_balancesEntities() {
        val now = System.currentTimeMillis()
        val fileCandidates = (1..3).map { id ->
            FusionCandidate(
                id = id.toLong(), title = "File $id", snippet = "snippet", filePath = "/path/$id", fileType = "txt",
                modifiedAt = now, sizeBytes = 1000, bm25Score = 10.0 + id, denseScore = 0.8 + (id * 0.05),
                entityType = EntityType.FILE
            )
        }
        val appCandidates = (4..6).map { id ->
            FusionCandidate(
                id = id.toLong(), title = "App $id", snippet = "snippet", filePath = "pkg.$id", fileType = "app",
                modifiedAt = now, sizeBytes = 0, bm25Score = 1.0 + id, denseScore = 0.4 + (id * 0.01),
                entityType = EntityType.APP
            )
        }

        val all = fileCandidates + appCandidates
        val ranker = FusionRanker()

        // PER_TYPE_NORMALIZATION
        val perTypeRanked = ranker.rank("query", all, FusionMode.PER_TYPE_NORMALIZATION)
        
        println("GLOBAL TOP 5: " + ranker.rank("query", all, FusionMode.GLOBAL_NORMALIZATION).take(5).map { "${it.title} (${it.entityType}) score=${it.finalScore}" })
        println("PER_TYPE TOP 5: " + perTypeRanked.take(5).map { "${it.title} (${it.entityType}) score=${it.finalScore}" })

        // Top 2 should now likely include the best File AND the best App because they are normalized within their groups
        val top2EntityTypes = perTypeRanked.take(2).map { it.entityType }.toSet()
        assertTrue("Per-type normalization should allow top results from different groups", 
            top2EntityTypes.contains(EntityType.FILE) && top2EntityTypes.contains(EntityType.APP))
        
        val bestFile = perTypeRanked.find { it.entityType == EntityType.FILE }
        val bestApp = perTypeRanked.find { it.entityType == EntityType.APP }
        
        // They should have comparable final scores now
        assertEquals("Best file and best app should have similar final scores after per-type normalization",
            bestFile!!.finalScore, bestApp!!.finalScore, 0.2)
    }
}
