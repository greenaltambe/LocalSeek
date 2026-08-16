package com.augt.localseek

import com.augt.localseek.data.ChunkWithMetadata
import com.augt.localseek.indexing.TextChunker
import com.augt.localseek.retrieval.ChunkAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ValidationTest {

    @Test
    fun textChunker_generatesOverlappingChunksWithOffsets() {
        val chunker = TextChunker(chunkSize = 6, overlap = 2)
        val text = (1..12).joinToString(" ") { "token$it" }
        val title = "My Document"

        val chunks = chunker.chunkDocument(fileId = 42L, text = text, title = title)

        assertEquals(3, chunks.size)
        assertEquals(0, chunks[0].startOffset)
        assertEquals(6, chunks[0].endOffset)
        assertEquals(title, chunks[0].title)
        assertEquals(title, chunks[1].title)
        assertEquals(title, chunks[2].title)
        assertTrue(chunks.all { it.parentFileId == 42L })
        // Title is prepended to chunk 0 text as well (legacy behavior)
        assertTrue(chunks[0].text.startsWith("$title."))
    }

    @Test
    fun textChunker_handlesEmptyBodyWithTitle() {
        val chunker = TextChunker()
        val title = "marksheet_2026.pdf"
        
        val chunks = chunker.chunkDocument(fileId = 1L, text = "", title = title)
        
        assertEquals(1, chunks.size)
        assertEquals(title, chunks[0].title)
        assertEquals(title, chunks[0].text)
        assertEquals(0, chunks[0].chunkIndex)
    }

    @Test
    fun chunkAggregator_groupsByParentFile_andKeepsTopThreeSnippets() {
        val hits = listOf(
            ChunkWithMetadata(1, 10, 0, "alpha one", 0, 2, "/docs/a.txt", "a.txt", "txt", 4096L, 1000L, -9.0f),
            ChunkWithMetadata(2, 10, 1, "alpha two", 2, 4, "/docs/a.txt", "a.txt", "txt", 4096L, 1000L, -8.0f),
            ChunkWithMetadata(3, 10, 2, "alpha three", 4, 6, "/docs/a.txt", "a.txt", "txt", 4096L, 1000L, -7.5f),
            ChunkWithMetadata(4, 10, 3, "alpha four", 6, 8, "/docs/a.txt", "a.txt", "txt", 4096L, 1000L, -6.0f),
            ChunkWithMetadata(5, 20, 0, "beta one", 0, 2, "/docs/b.txt", "b.txt", "txt", 2048L, 2000L, -12.0f)
        )

        val files = ChunkAggregator.aggregateChunks(hits)

        assertEquals(2, files.size)
        assertEquals(20L, files[0].parentFileId)
        assertEquals(-12.0f, files[0].bestScore)
        assertEquals(10L, files[1].parentFileId)
        assertEquals(3, files[1].relevantChunks.size)
        assertEquals(listOf("alpha one", "alpha two", "alpha three"), files[1].relevantChunks)
    }
}

