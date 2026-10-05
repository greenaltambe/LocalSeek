package com.augt.localseek.query.processing

import com.augt.localseek.search.query.EntityExtractor
import com.augt.localseek.search.query.IntentClassifier
import com.augt.localseek.search.query.QueryNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryProcessingCoreTest {

    @Test
    fun normalizer_removesUrlsAndEmails_andExpandsContractions() {
        val normalizer = QueryNormalizer()
        val result = normalizer.normalize("I'm searching https://example.com for notes at me@test.com")

        assertTrue(result.removedUrls.isNotEmpty())
        assertTrue(result.removedEmails.isNotEmpty())
        assertTrue(result.normalized.contains("i am"))
        assertFalse(result.normalized.contains("https"))
    }

    @Test
    fun extractor_detectsLanguageFileTypeAndDate() {
        val extractor = EntityExtractor()
        val entities = extractor.extract("kotlin tutorials pdf from yesterday")

        assertTrue(entities.any { it.type == EntityExtractor.EntityType.PROGRAMMING_LANGUAGE })
        assertTrue(entities.any { it.type == EntityExtractor.EntityType.FILE_TYPE })
        assertTrue(entities.any { it.type == EntityExtractor.EntityType.DATE_RELATIVE })
    }

    @Test
    fun intentClassifier_detectsFactFindingAndCodeSearch() {
        val classifier = IntentClassifier()

        val fact = classifier.classify(
            originalQuery = "what is kubernetes",
            normalizedQuery = "what is kubernetes",
            tokens = emptyList(),
            entities = emptyList()
        )
        assertEquals(IntentClassifier.Intent.FACT_FINDING, fact.intent)

        val code = classifier.classify(
            originalQuery = "kotlin coroutine example",
            normalizedQuery = "kotlin coroutine example",
            tokens = emptyList(),
            entities = listOf(
                EntityExtractor.Entity("kotlin", EntityExtractor.EntityType.PROGRAMMING_LANGUAGE, "kotlin", 0.9f)
            )
        )
        assertEquals(IntentClassifier.Intent.CODE_SEARCH, code.intent)
    }
}

