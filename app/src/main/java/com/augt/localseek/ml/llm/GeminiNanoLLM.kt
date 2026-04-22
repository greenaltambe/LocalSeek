package com.augt.localseek.ml.llm

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight Gemini Nano adapter placeholder.
 *
 * This class intentionally avoids direct compile-time dependency on AiCore SDK.
 * Real generation can be wired later behind reflection or official SDK APIs.
 */
class GeminiNanoLLM(private val context: Context) : OnDeviceLLM {

    companion object {
        private const val TAG = "GeminiNanoLLM"
        private val AICORE_PACKAGES = listOf(
            "com.google.android.aicore",
            "com.google.android.as",
            "com.google.android.gms"
        )

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

            if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return GeminiDiagnostics(
                    sdkVersion = sdk,
                    androidVersion = release,
                    manufacturer = manufacturer,
                    model = model,
                    aiCoreFound = false,
                    detectedPackage = null,
                    isAvailable = false,
                    reason = "Requires Android 14+ (SDK 34+)"
                )
            }

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

            return if (detectedPackage == null) {
                GeminiDiagnostics(
                    sdkVersion = sdk,
                    androidVersion = release,
                    manufacturer = manufacturer,
                    model = model,
                    aiCoreFound = false,
                    detectedPackage = null,
                    isAvailable = false,
                    reason = "AICore package not installed"
                )
            } else {
                GeminiDiagnostics(
                    sdkVersion = sdk,
                    androidVersion = release,
                    manufacturer = manufacturer,
                    model = model,
                    aiCoreFound = true,
                    detectedPackage = detectedPackage,
                    isAvailable = true,
                    reason = "AICore package detected"
                )
            }
        }

        fun isAvailable(context: Context): Boolean {
            return diagnose(context).isAvailable
        }
    }

    private var initialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        initialized = isAvailable(context)
        if (!initialized) {
            Log.w(TAG, "Gemini Nano is not available on this device")
        }
        initialized
    }

    override suspend fun generateAnswer(chunks: List<String>, query: String): LLMResponse {
        if (!initialized) {
            return LLMResponse.failure("Gemini Nano is not initialized")
        }

        // Placeholder response until direct AiCore integration is wired.
        return LLMResponse(
            answer = "Gemini Nano runtime is available but not yet integrated in this build.",
            sourceChunks = chunks.take(3),
            latencyMs = 0L
        )
    }
}

