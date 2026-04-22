package com.augt.localseek.ml.llm

import android.util.Log

/**
 * Safe JNI bridge wrapper for llama.cpp.
 * Native library is optional in this branch; calls return null/false if missing.
 */
object LlamaCppJNI {
    private const val TAG = "LlamaCppJNI"

    private val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("llama_jni")
    }.onFailure {
        Log.w(TAG, "llama_jni not loaded: ${it.message}")
    }.isSuccess

    private external fun nativeInitialize(modelPath: String, contextSize: Int): Long
    private external fun nativeGenerate(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int
    ): String

    private external fun nativeCleanup(contextPtr: Long)

    fun initialize(modelPath: String, contextSize: Int): Long? {
        if (!libraryLoaded) return null
        return runCatching { nativeInitialize(modelPath, contextSize) }
            .onFailure { Log.e(TAG, "nativeInitialize failed", it) }
            .getOrNull()
    }

    fun generate(
        contextPtr: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int
    ): String? {
        if (!libraryLoaded) return null
        return runCatching {
            nativeGenerate(contextPtr, prompt, maxTokens, temperature, topP, topK)
        }.onFailure {
            Log.e(TAG, "nativeGenerate failed", it)
        }.getOrNull()
    }

    fun cleanup(contextPtr: Long) {
        if (!libraryLoaded) return
        runCatching { nativeCleanup(contextPtr) }
            .onFailure { Log.e(TAG, "nativeCleanup failed", it) }
    }

    fun isReady(): Boolean = libraryLoaded
}

