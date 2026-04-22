package com.augt.localseek.ml.llm

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Phi-3-mini fallback runtime.
 *
 * Uses llama.cpp JNI when available; otherwise falls back to extractive answers
 * so the app keeps functioning without native binaries.
 */
class Phi3LLM(private val context: Context) : OnDeviceLLM {

    companion object {
        private const val TAG = "Phi3LLM"
        private const val MODEL_ASSET = "models/phi3.gguf"
        private const val MODEL_FILE = "phi3.gguf"

        private const val CONTEXT_SIZE = 4096
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.3f
        private const val TOP_P = 0.8f
        private const val TOP_K = 20

        data class Phi3Diagnostics(
            val sdkVersion: Int,
            val androidVersion: String,
            val modelAssetFound: Boolean,
            val jniReady: Boolean,
            val isAvailable: Boolean,
            val reason: String
        )

        fun diagnose(context: Context): Phi3Diagnostics {
            val sdk = Build.VERSION.SDK_INT
            val release = Build.VERSION.RELEASE
            val androidSupported = sdk >= Build.VERSION_CODES.P

            val assetFound = try {
                context.assets.open(MODEL_ASSET).use { true }
            } catch (_: Exception) {
                false
            }

            val downloadedFile = resolveDownloadedModel(context)
            val modelFound = assetFound || downloadedFile != null
            val jniReady = LlamaCppJNI.isReady()
            val available = androidSupported && modelFound
            val reason = when {
                !androidSupported -> "Requires Android 9+"
                !modelFound -> "Model missing (assets/models/phi3.gguf or downloaded file)"
                jniReady -> "Model found and llama_jni loaded"
                else -> "Model found; JNI missing (extractive fallback mode)"
            }

            return Phi3Diagnostics(
                sdkVersion = sdk,
                androidVersion = release,
                modelAssetFound = modelFound,
                jniReady = jniReady,
                isAvailable = available,
                reason = reason
            )
        }

        fun isAvailable(context: Context): Boolean {
            return diagnose(context).isAvailable
        }

        fun resolveDownloadedModel(context: Context): File? {
            val modelFile = File(File(context.filesDir, "models"), MODEL_FILE)
            return if (modelFile.exists() && modelFile.length() > 0L) modelFile else null
        }
    }

    private val extractiveFallback = ExtractiveOnDeviceLLM()
    private var contextPtr: Long? = null
    private var initialized = false
    private var modelPath: String? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) {
            Log.w(TAG, "Phi-3 model is missing")
            return@withContext false
        }

        val extracted = resolveModelFileForRuntime()
        if (extracted == null) {
            initialized = false
            return@withContext false
        }

        modelPath = extracted.absolutePath
        contextPtr = LlamaCppJNI.initialize(extracted.absolutePath, CONTEXT_SIZE)
        initialized = true
        if (contextPtr == null) {
            Log.w(TAG, "llama.cpp JNI unavailable; using extractive fallback for answers")
        }
        true
    }

    override suspend fun generateAnswer(chunks: List<String>, query: String): LLMResponse {
        val start = System.currentTimeMillis()
        if (!initialized) {
            return LLMResponse.failure("Phi-3 not initialized", System.currentTimeMillis() - start)
        }

        val prompt = buildRagPrompt(chunks, query)
        val ptr = contextPtr
        if (ptr == null) {
            val fallback = extractiveFallback.generateAnswer(chunks, query)
            return fallback.copy(latencyMs = System.currentTimeMillis() - start)
        }

        val generated = withContext(Dispatchers.IO) {
            LlamaCppJNI.generate(
                contextPtr = ptr,
                prompt = prompt,
                maxTokens = MAX_TOKENS,
                temperature = TEMPERATURE,
                topP = TOP_P,
                topK = TOP_K
            )
        }

        return if (generated.isNullOrBlank()) {
            val fallback = extractiveFallback.generateAnswer(chunks, query)
            fallback.copy(latencyMs = System.currentTimeMillis() - start)
        } else {
            LLMResponse(
                answer = generated.trim(),
                sourceChunks = chunks.take(3),
                latencyMs = System.currentTimeMillis() - start
            )
        }
    }

    fun close() {
        contextPtr?.let { LlamaCppJNI.cleanup(it) }
        contextPtr = null
    }

    private fun resolveModelFileForRuntime(): File? {
        val downloaded = resolveDownloadedModel(context)
        if (downloaded != null) return downloaded
        return extractModelToCache()
    }

    private fun extractModelToCache(): File? {
        return runCatching {
            val modelDir = File(context.cacheDir, "models").apply { mkdirs() }
            val out = File(modelDir, MODEL_FILE)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open(MODEL_ASSET).use { input ->
                    FileOutputStream(out).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Extracted phi3.gguf to cache (${out.length() / 1024 / 1024} MB)")
            }
            out
        }.onFailure {
            Log.e(TAG, "Failed to extract Phi-3 model", it)
        }.getOrNull()
    }

    private fun buildRagPrompt(chunks: List<String>, query: String): String {
        val contextText = chunks.take(5).joinToString("\n\n") { "```\n$it\n```" }
        return """
<|system|>
You are a helpful assistant. Use only the provided excerpts.
If the answer is not present, say you do not have enough information.
<|end|>
<|user|>
Document Excerpts:
$contextText

Question: $query
<|end|>
<|assistant|>
""".trimIndent()
    }
}

