package com.augt.localseek.ml.llm

interface OnDeviceLLM {
    suspend fun generateAnswer(chunks: List<String>, query: String): LLMResponse
}

data class LLMResponse(
    val answer: String = "",
    val sourceChunks: List<String> = emptyList(),
    val error: String? = null,
    val latencyMs: Long = 0L
) {
    companion object {
        fun failure(error: String, latencyMs: Long = 0L): LLMResponse =
            LLMResponse(answer = "", sourceChunks = emptyList(), error = error, latencyMs = latencyMs)
    }
}

