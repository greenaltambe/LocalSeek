package com.augt.localseek.indexing

import com.augt.localseek.data.DocumentChunk

class TextChunker(
    private val chunkSize: Int = 150,
    private val overlap: Int = 40
) {
    /**
     * Chunks a document into chunks of [chunkSize] tokens.
     * @param title if provided, prepended to the first chunk text. 
     *              If the body [text] is empty, a single title-only chunk is created.
     */
    fun chunkDocument(fileId: Long, text: String, title: String? = null): List<DocumentChunk> {
        val tokens = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        
        if (tokens.isEmpty() && (title == null || title.isBlank())) return emptyList()

        val chunks = mutableListOf<DocumentChunk>()
        val step = (chunkSize - overlap).coerceAtLeast(1)
        var chunkIndex = 0
        var position = 0

        // Handle case where document is empty but title is present (e.g. scanned PDF)
        if (tokens.isEmpty() && title != null && title.isNotBlank()) {
            chunks.add(
                DocumentChunk(
                    parentFileId = fileId,
                    chunkIndex = 0,
                    text = title,
                    title = title,
                    startOffset = 0,
                    endOffset = 0,
                    embedding = null // Explicitly null for title-only chunks
                )
            )
            return chunks
        }

        while (position < tokens.size) {
            val end = minOf(position + chunkSize, tokens.size)
            var chunkText = tokens.subList(position, end).joinToString(" ")

            // Prepend title ONLY to the first chunk (Legacy behavior, kept for recall boost)
            if (chunkIndex == 0 && title != null && title.isNotBlank()) {
                chunkText = "$title. $chunkText"
            }

            chunks.add(
                DocumentChunk(
                    parentFileId = fileId,
                    chunkIndex = chunkIndex,
                    text = chunkText,
                    title = title ?: "",
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
