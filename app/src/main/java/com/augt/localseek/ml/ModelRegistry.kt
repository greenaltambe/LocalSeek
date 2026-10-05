package com.augt.localseek.ml

import android.content.Context
import android.util.Log
import com.augt.localseek.ml.clip.ClipImageEncoder
import com.augt.localseek.ml.clip.ClipTextEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized registry for process-wide ML model instances in LocalSeek.
 *
 * Implements Phase 2 of the implementation plan:
 * - Exactly one instance per process for each shared ML model (DenseEncoder, CrossEncoder, ClipTextEncoder, ClipImageEncoder).
 * - Centralizes model loading and prevents duplicate in-memory interpreters.
 * - Thread-safe lazy loading with explicit lifecycle management.
 * - Concurrency tracking instrumentation to observe serialization vs concurrent inference in practice.
 */
class ModelRegistry(
    private val context: Context,
    denseEncoder: DenseEncoder? = null,
    crossEncoder: CrossEncoder? = null,
    clipTextEncoder: ClipTextEncoder? = null,
    clipImageEncoder: ClipImageEncoder? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "ModelRegistry"

        private fun logI(tag: String, msg: String) {
            try {
                Log.i(tag, msg)
            } catch (_: Throwable) {
                // Safe fallback for local JVM unit test environments
            }
        }

        private fun logW(tag: String, msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
            } catch (_: Throwable) {
                // Safe fallback for local JVM unit test environments
            }
        }
    }

    private val appContext = context.applicationContext

    // Concurrency tracking instrumentation
    private val activeInferenceCount = AtomicInteger(0)
    private val peakInferenceCount = AtomicInteger(0)
    private val totalInferenceCalls = AtomicInteger(0)

    @Volatile private var _denseEncoder: DenseEncoder? = denseEncoder
    @Volatile private var _crossEncoder: CrossEncoder? = crossEncoder
    @Volatile private var _clipTextEncoder: ClipTextEncoder? = clipTextEncoder
    @Volatile private var _clipImageEncoder: ClipImageEncoder? = clipImageEncoder

    val denseEncoder: DenseEncoder
        get() = _denseEncoder ?: synchronized(this) {
            _denseEncoder ?: run {
                logI(TAG, "Initializing shared DenseEncoder (MiniLM)")
                DenseEncoder(appContext).also { _denseEncoder = it }
            }
        }

    val crossEncoder: CrossEncoder
        get() = _crossEncoder ?: synchronized(this) {
            _crossEncoder ?: run {
                logI(TAG, "Initializing shared CrossEncoder")
                CrossEncoder(appContext).also { _crossEncoder = it }
            }
        }

    val clipTextEncoder: ClipTextEncoder
        get() = _clipTextEncoder ?: synchronized(this) {
            _clipTextEncoder ?: run {
                logI(TAG, "Initializing shared ClipTextEncoder")
                ClipTextEncoder(appContext).also { _clipTextEncoder = it }
            }
        }

    val clipImageEncoder: ClipImageEncoder
        get() = _clipImageEncoder ?: synchronized(this) {
            _clipImageEncoder ?: run {
                logI(TAG, "Initializing shared ClipImageEncoder")
                ClipImageEncoder(appContext).also { _clipImageEncoder = it }
            }
        }

    val isDenseEncoderInitialized: Boolean get() = _denseEncoder != null
    val isCrossEncoderInitialized: Boolean get() = _crossEncoder != null
    val isClipTextEncoderInitialized: Boolean get() = _clipTextEncoder != null
    val isClipImageEncoderInitialized: Boolean get() = _clipImageEncoder != null

    fun getActiveInferenceCount(): Int = activeInferenceCount.get()
    fun getPeakInferenceCount(): Int = peakInferenceCount.get()
    fun getTotalInferenceCalls(): Int = totalInferenceCalls.get()

    /**
     * Executes a model inference block while recording active and peak concurrency metrics.
     */
    fun <T> recordInference(block: () -> T): T {
        val current = activeInferenceCount.incrementAndGet()
        totalInferenceCalls.incrementAndGet()
        peakInferenceCount.updateAndGet { maxOf(it, current) }
        try {
            return block()
        } finally {
            activeInferenceCount.decrementAndGet()
        }
    }

    override fun close() {
        logI(TAG, "Closing ModelRegistry and releasing all loaded models")
        synchronized(this) {
            try {
                _denseEncoder?.close()
            } catch (e: Exception) {
                logW(TAG, "Error closing DenseEncoder", e)
            }
            _denseEncoder = null

            try {
                _crossEncoder?.close()
            } catch (e: Exception) {
                logW(TAG, "Error closing CrossEncoder", e)
            }
            _crossEncoder = null

            try {
                _clipTextEncoder?.close()
            } catch (e: Exception) {
                logW(TAG, "Error closing ClipTextEncoder", e)
            }
            _clipTextEncoder = null

            try {
                _clipImageEncoder?.close()
            } catch (e: Exception) {
                logW(TAG, "Error closing ClipImageEncoder", e)
            }
            _clipImageEncoder = null
        }
    }
}
