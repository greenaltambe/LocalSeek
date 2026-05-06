package com.augt.localseek.ml.llm

import android.content.Context
import android.os.Build
import android.util.Log

class LLMProvider(private val context: Context) {

    companion object {
        private const val TAG = "LLMProvider"
    }

    private var selectedLLM: OnDeviceLLM? = null

    suspend fun getAvailableLLM(): OnDeviceLLM? {
        selectedLLM = null
        val diagnostics = getDiagnostics()
        Log.d(TAG, "=== LLM Provider Detection ===")
        Log.d(TAG, "Summary: ${diagnostics.summary}")

        Log.d(TAG, "Trying Gemini initialization")
        val gemini = GeminiNanoLLM(context)
        try {
            if (gemini.initialize()) {
                Log.d(TAG, "Gemini initialized successfully")
                selectedLLM = gemini
                return gemini
            }
            Log.w(TAG, "Gemini initialization returned false")
        } catch (e: Exception) {
            Log.e(TAG, "Gemini initialization error", e)
        }

        if (Phi3LLM.isAvailable(context)) {
            Log.d(TAG, "Phi-3 available, initializing")
            val phi3 = Phi3LLM(context)
            return try {
                if (phi3.initialize()) {
                    Log.d(
                        TAG,
                        if (diagnostics.phi3JniReady) {
                            "Phi-3 initialized with llama.cpp JNI"
                        } else {
                            "Phi-3 initialized in extractive fallback mode (JNI missing)"
                        }
                    )
                    selectedLLM = phi3
                    phi3
                } else {
                    Log.w(TAG, "Phi-3 initialization returned false")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Phi-3 initialization error", e)
                null
            }
        }

        Log.w(TAG, "No usable LLM found; running search-only mode")
        return null
    }

    fun getCapabilities(): LLMCapabilities {
        return when (val llm = selectedLLM) {
            is GeminiNanoLLM -> LLMCapabilities(
                name = "Gemini",
                provider = "Google Generative AI",
                maxTokens = 2048,
                estimatedLatency = 1000,
                supportsStreaming = false,
                memoryImpact = "Low (cloud)",
                isAvailable = true
            )

            is Phi3LLM -> LLMCapabilities(
                name = "Phi-3-mini",
                provider = "Phi-3 runtime",
                maxTokens = 512,
                estimatedLatency = 1500,
                supportsStreaming = false,
                memoryImpact = "Medium (~600MB)",
                isAvailable = true,
                requiresDownload = false,
                requiresImplementation = null
            )

            else -> LLMCapabilities(
                name = "None",
                provider = "N/A",
                maxTokens = 0,
                estimatedLatency = 0,
                supportsStreaming = false,
                memoryImpact = "N/A",
                isAvailable = false,
                requiresDownload = true,
                requiresImplementation = null
            )
        }
    }

    fun getDiagnostics(): LLMDiagnostics {
        val gemini = GeminiNanoLLM.diagnose(context)
        val phi3 = Phi3LLM.diagnose(context)
        return LLMDiagnostics(
            sdkVersion = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE,
            aiCoreFound = gemini.isAvailable,
            phi3Found = phi3.modelAssetFound,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            geminiReason = gemini.reason,
            phi3Reason = phi3.reason,
            detectedAiCorePackage = gemini.detectedPackage,
            phi3JniReady = phi3.jniReady,
            summary = when {
                gemini.isAvailable -> "Gemini configured"
                phi3.isAvailable && phi3.jniReady -> "Phi-3 available (llama.cpp JNI)"
                phi3.isAvailable -> "Phi-3 available (extractive fallback mode)"
                else -> "No LLM available"
            }
        )
    }
}

data class LLMCapabilities(
    val name: String,
    val provider: String,
    val maxTokens: Int,
    val estimatedLatency: Long,
    val supportsStreaming: Boolean,
    val memoryImpact: String,
    val isAvailable: Boolean = true,
    val requiresDownload: Boolean = false,
    val requiresImplementation: String? = null
)

data class LLMDiagnostics(
    val sdkVersion: Int,
    val androidVersion: String,
    val aiCoreFound: Boolean,
    val phi3Found: Boolean,
    val manufacturer: String,
    val model: String,
    val geminiReason: String,
    val phi3Reason: String,
    val detectedAiCorePackage: String?,
    val phi3JniReady: Boolean,
    val summary: String
)

