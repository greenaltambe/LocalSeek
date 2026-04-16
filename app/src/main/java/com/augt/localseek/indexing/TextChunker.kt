package com.augt.localseek.indexing

object TextChunker {
    /**
     * Splits text into overlapping chunks.
     * @param chunkSize Number of words per chunk
     * @param overlap Number of words to repeat from the previous chunk
     */
    fun split(text: String, chunkSize: Int = 150, overlap: Int = 50): List<String> {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size <= chunkSize) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            
            // Move start forward, but stay back by 'overlap' words
            start += (chunkSize - overlap)
            
            // Safety check to avoid infinite loops if overlap >= chunkSize
            if (chunkSize <= overlap) break
        }
        return chunks
    }
}
