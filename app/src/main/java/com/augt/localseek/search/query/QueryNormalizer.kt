package com.augt.localseek.search.query

import java.text.Normalizer as JavaNormalizer

class QueryNormalizer {

    companion object {
        private val CONTRACTIONS = mapOf(
            "don't" to "do not",
            "won't" to "will not",
            "can't" to "cannot",
            "it's" to "it is",
            "i'm" to "i am",
            "we're" to "we are",
            "they're" to "they are",
            "you're" to "you are",
            "hasn't" to "has not",
            "haven't" to "have not",
            "isn't" to "is not",
            "aren't" to "are not",
            "wasn't" to "was not",
            "weren't" to "were not",
            "let's" to "let us",
            "that's" to "that is",
            "who's" to "who is",
            "what's" to "what is",
            "where's" to "where is",
            "when's" to "when is",
            "why's" to "why is",
            "how's" to "how is",
            "i've" to "i have",
            "we've" to "we have",
            "they've" to "they have",
            "you've" to "you have",
            "should've" to "should have",
            "would've" to "would have",
            "could've" to "could have"
        )

        private val URL_PATTERN = Regex("https?://[^\\s]+")
        private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        private val SPECIAL_CHARS = Regex("[^a-z0-9\\s'-]")
        private val MULTI_SPACE = Regex("\\s+")
        private val DIACRITIC_MARKS = Regex("\\p{M}")
    }

    data class NormalizedQuery(
        val original: String,
        val normalized: String,
        val removedUrls: List<String>,
        val removedEmails: List<String>
    )

    fun normalize(query: String): NormalizedQuery {
        var processed = query
        val urls = URL_PATTERN.findAll(processed).map { it.value }.toList()
        val emails = EMAIL_PATTERN.findAll(processed).map { it.value }.toList()

        processed = processed.replace(URL_PATTERN, " ")
        processed = processed.replace(EMAIL_PATTERN, " ")
        processed = JavaNormalizer.normalize(processed, JavaNormalizer.Form.NFD)
        processed = processed.replace(DIACRITIC_MARKS, "")
        processed = processed.lowercase()

        CONTRACTIONS.forEach { (contraction, expansion) ->
            processed = processed.replace("\\b$contraction\\b".toRegex(), expansion)
        }

        processed = processed.replace(SPECIAL_CHARS, " ")
            .replace(MULTI_SPACE, " ")
            .trim()

        return NormalizedQuery(
            original = query,
            normalized = processed,
            removedUrls = urls,
            removedEmails = emails
        )
    }
}

