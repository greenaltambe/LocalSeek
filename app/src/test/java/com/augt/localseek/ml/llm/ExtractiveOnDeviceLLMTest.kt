package com.augt.localseek.ml.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractiveOnDeviceLLMTest {

    @Test
    fun generateAnswer_returnsSummary_whenRelevantChunksExist() = runBlocking {
        val llm = ExtractiveOnDeviceLLM()
        val chunks = listOf(
            "Kotlin coroutines use suspend functions to run async code.",
            "SQLite supports FTS5 for BM25 full-text ranking.",
            "Coroutines can be launched with viewModelScope in Android."
        )

        val response = llm.generateAnswer(chunks, "kotlin coroutines")

        assertTrue(response.error == null)
        assertFalse(response.answer.isBlank())
        assertTrue(response.sourceChunks.isNotEmpty())
    }

    @Test
    fun generateAnswer_returnsError_whenNoChunksProvided() = runBlocking {
        val llm = ExtractiveOnDeviceLLM()

        val response = llm.generateAnswer(emptyList(), "kotlin")

        assertTrue(response.error != null)
        assertTrue(response.answer.isBlank())
    }
}

