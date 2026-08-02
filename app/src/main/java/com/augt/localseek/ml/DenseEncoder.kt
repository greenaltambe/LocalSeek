package com.augt.localseek.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DenseEncoder(context: Context) {

    companion object {
        private const val TAG = "DenseEncoder"
        private const val MODEL_FILE = "minilm_optimized.tflite"
        private const val MAX_TOKENS = 128
        private const val EMBEDDING_SIZE = 384
    }

    private val tokenizer = BertTokenizer(context)
    private val interpreter: Interpreter?
    // Guard interpreter usage because TFLite Interpreter is not thread-safe.
    // Use a synchronized lock to protect all interpreter calls.
    private val lock = Object()
    // Flag to indicate the interpreter has been closed; protects against calls after close()
    @Volatile
    private var closed = false

    init {
        interpreter = try {
            val modelBuffer = loadModelFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setUseNNAPI(true)
                setNumThreads(4)
            }
            Interpreter(modelBuffer, options).also { interp ->
                Log.i(TAG, "Loaded $MODEL_FILE | NNAPI=true | threads=4")

                for (i in 0 until interp.inputTensorCount) {
                    val tensor = interp.getInputTensor(i)
                    Log.d(TAG, "Input $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
                }
                for (i in 0 until interp.outputTensorCount) {
                    val tensor = interp.getOutputTensor(i)
                    Log.d(TAG, "Output $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DenseEncoder model", e)
            null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    /**
     * Converts text into a normalized 384-dimensional semantic vector.
     * Returns an empty FloatArray if the encoder is closed or interpreter is null.
     */
    fun encode(text: String): FloatArray {
        // quick guard: if already closed or interpreter is unavailable, return empty
        if (closed) return FloatArray(0)

        val currentInterpreter = interpreter ?: return FloatArray(0)

        return try {
            val (inputIds, attentionMask) = tokenizer.tokenize(text, MAX_TOKENS)

            val outputEmbedding = Array(1) { FloatArray(EMBEDDING_SIZE) }

            // serialize access to the Interpreter to avoid concurrent native calls
            synchronized(lock) {
                if (!closed && interpreter != null) {
                    currentInterpreter.runForMultipleInputsOutputs(
                        arrayOf(
                            arrayOf(inputIds),
                            arrayOf(attentionMask)
                        ),
                        mapOf(0 to outputEmbedding)
                    )
                } else {
                    return FloatArray(0)
                }
            }

            val result = l2Normalize(outputEmbedding[0])
            if (result.all { it == 0.0f }) {
                Log.w(TAG, "Warning: Encoder returned an all-zero vector")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed", e)
            FloatArray(0)
        }
    }

    fun encodeBatch(texts: List<String>, batchSize: Int = 8): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        return texts.chunked(batchSize.coerceAtLeast(1)).flatMap { batch ->
            batch.map { text -> encode(text) }
        }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 0f) return vector
        return FloatArray(vector.size) { idx -> vector[idx] / norm }
    }

    fun close() {
        // ensure close does not race with in-flight inference
        synchronized(lock) {
            // mark closed so subsequent encode() calls return safely
            closed = true
            interpreter?.close()
        }
    }
}

class CrossEncoder(context: Context) {

    companion object {
        private const val TAG = "CrossEncoder"
        private const val MAX_LENGTH = 256
        private const val MODEL_FILE = "models/cross_encoder.tflite"
    }

    private val tokenizer = BertTokenizer(context)
    private val interpreter: Interpreter?
    // Guard interpreter usage because TFLite Interpreter is not thread-safe.
    // Use a coroutines Mutex so suspend functions can serialize access safely.
    private val interpreterMutex = Mutex()
    // Flag to indicate the interpreter has been closed; protects against calls after close()
    @Volatile
    private var closed = false

    val isAvailable: Boolean
        get() = interpreter != null && !closed

    init {
        interpreter = try {
            val modelBuffer = loadModelFile(context, MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(false)
            }
            Interpreter(modelBuffer, options).also {
                Log.i(TAG, "Loaded $MODEL_FILE")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cross-encoder model unavailable; reranking will use fallback", e)
            null
        }
    }

    suspend fun score(query: String, document: String): Float {
        // quick guard: if already closed, return 0f immediately
        if (closed) return 0f

        val current = interpreter ?: return 0f

        return try {
            val (inputIds, attentionMask) = tokenizer.tokenize("$query [SEP] $document", MAX_LENGTH)
            val output = FloatArray(1)

            // serialize access to the Interpreter to avoid concurrent native calls
            var ran = false
            interpreterMutex.withLock {
                if (!closed) {
                    current.runForMultipleInputsOutputs(
                        arrayOf(arrayOf(inputIds), arrayOf(attentionMask)),
                        mapOf(0 to output)
                    )
                    ran = true
                }
            }

            if (ran) output[0] else 0f
        } catch (e: Exception) {
            Log.e(TAG, "Cross-encoder scoring failed", e)
            0f
        }
    }

    fun close() {
        // ensure close does not race with in-flight inference
        // Use runBlocking here so callers can call close() from non-suspending contexts
        runBlocking {
            interpreterMutex.withLock {
                // mark closed so subsequent score() calls return safely
                closed = true
                interpreter?.close()
            }
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }
}

