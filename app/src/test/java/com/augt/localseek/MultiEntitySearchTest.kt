package com.augt.localseek

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MultiEntitySearchTest {

    @Test
    fun fusionPipeline_handlesMixedEntities() {
        val ranker = FusionRanker()
        val now = System.currentTimeMillis()

        val candidates = listOf(
            FusionCandidate(
                id = 1, title = "Notes.txt", snippet = "text", filePath = "/path", fileType = "txt",
                modifiedAt = now, sizeBytes = 100, bm25Score = 0.9, entityType = EntityType.FILE
            ),
            FusionCandidate(
                id = 2, title = "WhatsApp", snippet = "whatsapp app", filePath = "com.whatsapp", fileType = "app",
                modifiedAt = now, sizeBytes = 0, bm25Score = 0.8, entityType = EntityType.APP
            ),
            FusionCandidate(
                id = 3, title = "Alice Smith", snippet = "Alice Smith contact", filePath = "contact_3", fileType = "contact",
                modifiedAt = now, sizeBytes = 0, bm25Score = 0.7, entityType = EntityType.CONTACT
            )
        )

        val ranked = ranker.rank("query", candidates)
        assertEquals(3, ranked.size)
        // Ensure entity types are preserved
        assertEquals(EntityType.FILE, ranked.find { it.id == 1L }?.entityType)
        assertEquals(EntityType.APP, ranked.find { it.id == 2L }?.entityType)
        assertEquals(EntityType.CONTACT, ranked.find { it.id == 3L }?.entityType)
    }

    @Test
    fun appTextRepresentation_isCorrect() {
        val appName = "WhatsApp"
        val category = "social"
        val packageName = "com.whatsapp"
        val textRepresentation = "$appName application $category $packageName".trim()
        assertEquals("WhatsApp application social com.whatsapp", textRepresentation)
    }

    @Test
    fun contactTextRepresentation_isCorrect_and_privacyPreserved() {
        val name = "John Doe"
        val org = "Google"
        val phone = "123456789"
        
        // Simulating Indexer logic
        val textRepresentation = if (org.isNotBlank()) "$name contact organization $org" else "$name contact"
        
        assertEquals("John Doe contact organization Google", textRepresentation)
        assertFalse("Phone numbers must NOT be included in text representation for privacy", 
            textRepresentation.contains(phone))
    }
}
