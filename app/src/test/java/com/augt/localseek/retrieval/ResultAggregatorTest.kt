package com.augt.localseek.retrieval

import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultAggregatorTest {

    @Test
    fun `aggregateToFiles should collapse multiple chunks from the same file`() {
        val query = "test"
        val chunks = listOf(
            SearchResult(
                id = 1,
                title = "File A",
                snippet = "chunk 1",
                filePath = "/path/to/fileA.md",
                fileType = "md",
                score = 0.8f,
                modifiedAt = 1000L,
                entityType = EntityType.FILE
            ),
            SearchResult(
                id = 2,
                title = "File A",
                snippet = "chunk 2",
                filePath = "/path/to/fileA.md",
                fileType = "md",
                score = 0.9f,
                modifiedAt = 1000L,
                entityType = EntityType.FILE
            )
        )

        val aggregated = ResultAggregator.aggregateToFiles(chunks, query)

        assertEquals(1, aggregated.size)
        assertEquals("File A", aggregated[0].title)
        assertEquals(0.9, aggregated[0].bestScore, 0.001)
        assertEquals(2, aggregated[0].snippets.size)
    }

    @Test
    fun `aggregateToFiles should collapse multiple entries for the same app`() {
        val query = "whatsapp"
        val results = listOf(
            SearchResult(
                id = 10,
                title = "WhatsApp",
                snippet = "messenger",
                filePath = "com.whatsapp",
                fileType = "app",
                score = 0.95f,
                modifiedAt = 2000L,
                entityType = EntityType.APP
            ),
            SearchResult(
                id = 11,
                title = "WhatsApp",
                snippet = "messenger app",
                filePath = "com.whatsapp",
                fileType = "app",
                score = 0.90f,
                modifiedAt = 2000L,
                entityType = EntityType.APP
            )
        )

        val aggregated = ResultAggregator.aggregateToFiles(results, query)

        assertEquals(1, aggregated.size)
        assertEquals("WhatsApp", aggregated[0].title)
        assertEquals(0.95, aggregated[0].bestScore, 0.001)
    }
}
