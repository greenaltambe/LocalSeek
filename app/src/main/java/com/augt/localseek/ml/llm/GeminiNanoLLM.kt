package com.augt.localseek.ml.llm

import android.content.Context
import android.os.Build
import android.util.Log
import com.augt.localseek.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * Gemini runtime adapter using Google Generative AI SDK.
 */
class GeminiNanoLLM(private val context: Context) : OnDeviceLLM {

    companion object {
        private const val TAG = "GeminiNanoLLM"
        private val AICORE_PACKAGES = listOf(
            "com.google.android.aicore",
            "com.google.android.as",
            "com.google.android.gms"
        )
        private const val MODEL_NAME = "gemini-1.5-flash"
        private const val INIT_TIMEOUT_MS = 6_000L
        private const val GENERATION_TIMEOUT_MS = 15_000L

        private fun resolveApiKeyFromContext(context: Context): String {
            val fromBuildConfig = BuildConfig.GEMINI_API_KEY.trim()
            if (fromBuildConfig.isNotBlank()) return fromBuildConfig

            return runCatching {
                context.assets.open("gemini_key.txt").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
        }

        data class GeminiDiagnostics(
            val sdkVersion: Int,
            val androidVersion: String,
            val manufacturer: String,
            val model: String,
            val aiCoreFound: Boolean,
            val detectedPackage: String?,
            val isAvailable: Boolean,
            val reason: String
        )

        fun diagnose(context: Context): GeminiDiagnostics {
            Log.d(TAG, "=== Gemini Nano Availability Check ===")

            val sdk = Build.VERSION.SDK_INT
            val release = Build.VERSION.RELEASE
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            Log.d(TAG, "Android SDK: $sdk ($release)")
            Log.d(TAG, "Device: $manufacturer $model")

            var detectedPackage: String? = null
            AICORE_PACKAGES.forEach { pkg ->
                try {
                    val info = context.packageManager.getPackageInfo(pkg, 0)
                    detectedPackage = pkg
                    Log.d(TAG, "Found package: $pkg (${info.versionName})")
                    return@forEach
                } catch (_: Exception) {
                    Log.d(TAG, "Package not found: $pkg")
                }
            }

            val hasApiKey = resolveApiKeyFromContext(context).isNotBlank()
            return if (!hasApiKey) {
                GeminiDiagnostics(
                    sdkVersion = sdk,
                    androidVersion = release,
                    manufacturer = manufacturer,
                    model = model,
                    aiCoreFound = detectedPackage != null,
                    detectedPackage = detectedPackage,
                    isAvailable = false,
                    reason = "GEMINI_API_KEY is missing"
                )
            } else {
                GeminiDiagnostics(
                    sdkVersion = sdk,
                    androidVersion = release,
                    manufacturer = manufacturer,
                    model = model,
                    aiCoreFound = detectedPackage != null,
                    detectedPackage = detectedPackage,
                    isAvailable = true,
                    reason = if (detectedPackage != null) {
                        "API key configured (AICore package also detected)"
                    } else {
                        "API key configured (cloud Gemini path)"
                    }
                )
            }
        }

        fun isAvailable(context: Context): Boolean {
            return diagnose(context).isAvailable
        }
    }

    private var initialized = false
    private var model: GenerativeModel? = null
    private var failureReason: String = "Not initialized"

    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        val diagnostics = diagnose(context)
        if (!diagnostics.isAvailable) {
            failureReason = diagnostics.reason
            Log.w(TAG, "Gemini unavailable: $failureReason")
            initialized = false
            return@withContext false
        }

        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            failureReason = "GEMINI_API_KEY is empty. Add it to local.properties and rebuild."
            Log.w(TAG, failureReason)
            initialized = false
            return@withContext false
        }

        return@withContext try {
            model = GenerativeModel(
                modelName = MODEL_NAME,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.3f
                    maxOutputTokens = 512
                    topK = 40
                    topP = 0.95f
                }
            )

            withTimeout(INIT_TIMEOUT_MS) {
                model?.generateContent("Respond with: ok")
            }
            initialized = true
            failureReason = "Ready"
            Log.i(TAG, "Gemini initialized successfully")
            true
        } catch (e: Exception) {
            initialized = false
            model = null
            failureReason = "Init failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Gemini initialization failed: $failureReason")
            false
        }
    }

    override suspend fun generateAnswer(chunks: List<String>, query: String): LLMResponse {
        val activeModel = model
        if (!initialized || activeModel == null) {
            return LLMResponse.failure("Gemini is not initialized: $failureReason")
        }

        val start = System.currentTimeMillis()
        val prompt = buildPrompt(query, chunks)

        return try {
            val response = withTimeout(GENERATION_TIMEOUT_MS) {
                activeModel.generateContent(prompt)
            }
            val text = response.text?.trim().orEmpty()
            val latency = System.currentTimeMillis() - start
            if (text.isBlank()) {
                LLMResponse.failure("Gemini returned an empty response", latency)
            } else {
                LLMResponse(
                    answer = text,
                    sourceChunks = chunks.take(3),
                    latencyMs = latency
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini generation failed", e)
            LLMResponse.failure("Gemini error: ${e.message ?: "unknown"}", System.currentTimeMillis() - start)
        }
    }

    private fun resolveApiKey(): String {
        val fromBuildConfig = BuildConfig.GEMINI_API_KEY.trim()
        if (fromBuildConfig.isNotBlank()) {
            Log.d(TAG, "Gemini key source: BuildConfig")
            return fromBuildConfig
        }

        val fromAsset = runCatching {
            context.assets.open("gemini_key.txt").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        if (fromAsset.isNotBlank()) {
            Log.d(TAG, "Gemini key source: assets/gemini_key.txt")
        }
        return fromAsset
    }

    private fun buildPrompt(query: String, chunks: List<String>): String {
        val contextText = chunks
            .asSequence()
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString("\n\n---\n\n") { it.take(800) }

        return """
            You are a helpful assistant that answers questions about the user's local documents.
            Use ONLY the provided excerpts.
            If the answer is not in the excerpts, say: I couldn't find that in your documents.
            Keep the answer concise and factual.

            DOCUMENT EXCERPTS:
            $contextText

            QUESTION: $query
            ANSWER:
        """.trimIndent()
    }
}

