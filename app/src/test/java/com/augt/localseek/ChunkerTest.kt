package com.augt.localseek

import com.augt.localseek.indexing.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun `chunking produces expected number of chunks`() {
        val chunker = TextChunker(chunkSize = 10, overlap = 3)
        val text = List(50) { "word" }.joinToString(" ")

        val chunks = chunker.chunkDocument(1L, text)

        assertTrue(chunks.size > 1)
        assertEquals(0, chunks.first().chunkIndex)
    }

    @Test
    fun `overlapping chunks share tokens`() {
        val chunker = TextChunker(chunkSize = 10, overlap = 3)
        val chunks = chunker.chunkDocument(1L, "a b c d e f g h i j k l m")

        assertTrue(chunks.size >= 2)
        val firstTokens = chunks[0].text.split(" ")
        val secondTokens = chunks[1].text.split(" ")
        val overlap = firstTokens.intersect(secondTokens.toSet())

        assertTrue(overlap.isNotEmpty())
        assertTrue(chunks[1].text.contains(firstTokens.last()))
    }
}

