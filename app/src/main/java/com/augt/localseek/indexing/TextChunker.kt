package com.augt.localseek.indexing

import com.augt.localseek.data.DocumentChunk

class TextChunker(
    private val chunkSize: Int = 150,
    private val overlap: Int = 40
) {
    fun chunkDocument(fileId: Long, text: String): List<DocumentChunk> {
        val tokens = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val chunks = mutableListOf<DocumentChunk>()
        val step = (chunkSize - overlap).coerceAtLeast(1)
        var chunkIndex = 0
        var position = 0

        while (position < tokens.size) {
            val end = minOf(position + chunkSize, tokens.size)
            val chunkText = tokens.subList(position, end).joinToString(" ")

            chunks.add(
                DocumentChunk(
                    parentFileId = fileId,
                    chunkIndex = chunkIndex,
                    text = chunkText,
                    startOffset = position,
                    endOffset = end
                )
            )

            position += step
            chunkIndex++
        }

        return chunks
    }
}
