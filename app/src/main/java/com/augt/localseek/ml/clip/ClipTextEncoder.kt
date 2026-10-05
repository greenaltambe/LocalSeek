package com.augt.localseek.ml.clip

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-Device CLIP Text Tower Encoder.
 *
 * Spec:
 * - Model Asset: models/clip/clip_text_encoder_int8.tflite (~27-121 MB)
 * - Input: input_ids [1, 77] (LongArray), attention_mask [1, 77] (LongArray)
 * - Output: 512-dimensional normalized FloatArray
 * - Thread-Safety: Protected via synchronized lock matching DenseEncoder pattern.
 */
class ClipTextEncoder(context: Context) {

    companion object {
        private const val TAG = "ClipTextEncoder"
        private const val MODEL_FILE = "models/clip/clip_text_encoder_fp16.tflite"
        const val EMBEDDING_SIZE = 512
        private const val OUTPUT_TENSOR_INDEX = 1 // Output 1: Identity_1 [1, 512]
    }

    private val tokenizer: ClipBpeTokenizer? = try {
        ClipBpeTokenizer(context)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize ClipBpeTokenizer", e)
        null
    }
    private val interpreter: Interpreter?

    private val lock = Object()

    @Volatile
    private var closed = false

    val isAvailable: Boolean
        get() = interpreter != null && (tokenizer?.isAvailable == true) && !closed

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
            Log.e(TAG, "Failed to load ClipTextEncoder model", e)
            null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        return ClipAssetPackManager.loadModelFile(context, modelName)
    }

    /**
     * Converts text into a normalized 512-dimensional CLIP text embedding.
     * Returns an empty FloatArray if closed or interpreter is null.
     */
    fun encode(text: String): FloatArray {
        if (closed) return FloatArray(0)
        val currentInterpreter = interpreter ?: return FloatArray(0)
        val currentTokenizer = tokenizer ?: return FloatArray(0)
        if (!currentTokenizer.isAvailable) return FloatArray(0)

        return try {
            val (inputIds, attentionMask) = currentTokenizer.tokenize(text)
            val outputEmbedding = Array(1) { FloatArray(EMBEDDING_SIZE) }

            synchronized(lock) {
                if (!closed && interpreter != null) {
                    currentInterpreter.runForMultipleInputsOutputs(
                        arrayOf(
                            arrayOf(inputIds),
                            arrayOf(attentionMask)
                        ),
                        mapOf(OUTPUT_TENSOR_INDEX to outputEmbedding)
                    )
                } else {
                    return FloatArray(0)
                }
            }

            val result = l2Normalize(outputEmbedding[0])
            if (result.all { it == 0.0f }) {
                Log.w(TAG, "Warning: ClipTextEncoder returned an all-zero vector")
            }
            result
        } catch (e: Exception) {
            // The text is the user's query: log only the exception class.
            Log.e(TAG, "Encoding failed (${e.javaClass.simpleName})")
            FloatArray(0)
        }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm <= 0f) return vector
        return FloatArray(vector.size) { idx -> vector[idx] / norm }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            interpreter?.close()
        }
    }
}
