package com.augt.localseek.ml.llm

import kotlin.math.min

/**
 * Lightweight fallback answerer that extracts best matching chunks and stitches
 * a concise answer. This keeps the LLM pipeline usable even when a true
 * generative model is not wired yet.
 */
class ExtractiveOnDeviceLLM : OnDeviceLLM {

    override suspend fun generateAnswer(chunks: List<String>, query: String): LLMResponse {
        val start = System.currentTimeMillis()
        if (query.isBlank()) {
            return LLMResponse.failure("Query is blank", System.currentTimeMillis() - start)
        }
        if (chunks.isEmpty()) {
            return LLMResponse.failure("No context chunks provided", System.currentTimeMillis() - start)
        }

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) {
            return LLMResponse.failure("Query has no searchable terms", System.currentTimeMillis() - start)
        }

        val ranked = chunks
            .asSequence()
            .map { chunk -> chunk to scoreChunk(chunk, queryTerms) }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { (_, score) -> score }
            .take(3)
            .toList()

        if (ranked.isEmpty()) {
            return LLMResponse.failure("No relevant context found", System.currentTimeMillis() - start)
        }

        val selectedChunks = ranked.map { it.first }
        val summary = selectedChunks
            .mapIndexed { index, text -> "${index + 1}. ${trimSentence(text)}" }
            .joinToString("\n")

        return LLMResponse(
            answer = summary,
            sourceChunks = selectedChunks,
            latencyMs = System.currentTimeMillis() - start
        )
    }

    private fun scoreChunk(chunk: String, queryTerms: Set<String>): Double {
        val chunkTerms = tokenize(chunk)
        if (chunkTerms.isEmpty()) return 0.0
        val overlap = chunkTerms.intersect(queryTerms)
        return overlap.size.toDouble() / queryTerms.size
    }

    private fun tokenize(text: String): Set<String> {
        return text
            .lowercase()
            .split("\\s+".toRegex())
            .map { it.replace("[^a-z0-9_]".toRegex(), "") }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun trimSentence(text: String, maxLen: Int = 220): String {
        val cleaned = text.trim().replace("\\s+".toRegex(), " ")
        if (cleaned.length <= maxLen) return cleaned
        return cleaned.substring(0, min(cleaned.length, maxLen)).trimEnd() + "..."
    }
}

