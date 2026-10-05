package com.augt.localseek

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.augt.localseek.ml.DenseEncoder
import com.augt.localseek.search.query.EntityExtractor
import com.augt.localseek.search.query.IntentClassifier
import com.augt.localseek.search.query.QueryProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueryProcessorInstrumentationTest {

    @Test
    fun testQueryProcessing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val encoder = DenseEncoder(context)
        val processor = QueryProcessor(context, encoder)

        val result = processor.process("find my kotlin notes from yesterday")

        assertEquals(IntentClassifier.Intent.LOOKUP, result.intent.intent)
        assertTrue(result.entities.any { it.type == EntityExtractor.EntityType.PROGRAMMING_LANGUAGE })
        assertTrue(result.entities.any { it.type == EntityExtractor.EntityType.DATE_RELATIVE })
        assertTrue(result.processingTimeMs < 50)

        encoder.close()
    }
}

