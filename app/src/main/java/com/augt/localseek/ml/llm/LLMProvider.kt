package com.augt.localseek.ml.llm

import android.content.Context
import android.os.Build
import android.util.Log

class LLMProvider(private val context: Context) {

    companion object {
        private const val TAG = "LLMProvider"
    }

    suspend fun getAvailableLLM(): OnDeviceLLM? {
        val diagnostics = getDiagnostics()
        Log.d(TAG, "=== LLM Provider Detection ===")
        Log.d(TAG, "Summary: ${diagnostics.summary}")

        if (GeminiNanoLLM.isAvailable(context)) {
            Log.d(TAG, "Gemini Nano available, initializing")
            val gemini = GeminiNanoLLM(context)
            return try {
                if (gemini.initialize()) {
                    Log.d(TAG, "Gemini Nano initialized successfully")
                    gemini
                } else {
                    Log.w(TAG, "Gemini Nano initialization returned false")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Nano initialization error", e)
                null
            }
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
        val diagnostics = getDiagnostics()
        return when {
            diagnostics.aiCoreFound -> LLMCapabilities(
                name = "Gemini Nano",
                provider = "Google AICore",
                maxTokens = 2048,
                estimatedLatency = 1000,
                supportsStreaming = true,
                memoryImpact = "Low (system-managed)",
                isAvailable = true
            )

            diagnostics.phi3Found -> LLMCapabilities(
                name = if (diagnostics.phi3JniReady) "Phi-3-mini" else "Phi-3-mini (Fallback)",
                provider = if (diagnostics.phi3JniReady) "llama.cpp" else "Extractive fallback",
                maxTokens = 512,
                estimatedLatency = 1500,
                supportsStreaming = false,
                memoryImpact = "Medium (~600MB)",
                isAvailable = true,
                requiresDownload = false,
                requiresImplementation = if (diagnostics.phi3JniReady) null else "llama.cpp JNI (optional for generation quality)"
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
            aiCoreFound = gemini.aiCoreFound,
            phi3Found = phi3.modelAssetFound,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            geminiReason = gemini.reason,
            phi3Reason = phi3.reason,
            detectedAiCorePackage = gemini.detectedPackage,
            phi3JniReady = phi3.jniReady,
            summary = when {
                gemini.isAvailable -> "Gemini available"
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

