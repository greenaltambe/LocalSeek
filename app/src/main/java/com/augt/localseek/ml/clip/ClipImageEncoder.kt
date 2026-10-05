package com.augt.localseek.ml.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * On-Device CLIP Image Tower Encoder.
 *
 * Spec:
 * - Model Asset: models/clip/clip_image_encoder_fp16.tflite (~167.7 MB)
 * - Input: pixel_values [1, 224, 224, 3] FloatArray (normalized RGB)
 * - Output: 512-dimensional normalized FloatArray
 * - Normalization:
 *     - scale = 1/255.0
 *     - mean = [0.48145466f, 0.4578275f, 0.40821073f]
 *     - std = [0.26862954f, 0.26130258f, 0.27577711f]
 * - Thread-Safety: Protected via synchronized lock matching DenseEncoder/ClipTextEncoder pattern.
 */
class ClipImageEncoder(context: Context) {

    companion object {
        private const val TAG = "ClipImageEncoder"
        private const val MODEL_FILE = "models/clip/clip_image_encoder_fp16.tflite"
        const val EMBEDDING_SIZE = 512
        private const val IMAGE_SIZE = 224
        private const val OUTPUT_TENSOR_INDEX = 1 // Output 1: Identity_1 [1, 512]

        // Standard OpenAI CLIP Image Normalization Constants
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private val interpreter: Interpreter?

    private val lock = Object()

    @Volatile
    private var closed = false

    val isAvailable: Boolean
        get() = interpreter != null && !closed

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
            Log.e(TAG, "Failed to load ClipImageEncoder model", e)
            null
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        return ClipAssetPackManager.loadModelFile(context, modelName)
    }

    /**
     * Converts a Bitmap into a normalized 512-dimensional CLIP image embedding.
     * Returns an empty FloatArray if closed or interpreter is null.
     */
    fun encode(bitmap: Bitmap): FloatArray {
        if (closed) return FloatArray(0)
        val currentInterpreter = interpreter ?: return FloatArray(0)

        return try {
            val inputTensor = preprocessImage(bitmap)
            val outputEmbedding = Array(1) { FloatArray(EMBEDDING_SIZE) }

            synchronized(lock) {
                if (!closed && interpreter != null) {
                    currentInterpreter.runForMultipleInputsOutputs(
                        arrayOf(inputTensor),
                        mapOf(OUTPUT_TENSOR_INDEX to outputEmbedding)
                    )
                } else {
                    return FloatArray(0)
                }
            }

            val result = l2Normalize(outputEmbedding[0])
            if (result.all { it == 0.0f }) {
                Log.w(TAG, "Warning: ClipImageEncoder returned an all-zero vector")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Image encoding failed", e)
            FloatArray(0)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val resized = if (bitmap.width == IMAGE_SIZE && bitmap.height == IMAGE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        }

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val input = Array(1) { Array(IMAGE_SIZE) { Array(IMAGE_SIZE) { FloatArray(3) } } }

        for (y in 0 until IMAGE_SIZE) {
            for (x in 0 until IMAGE_SIZE) {
                val pixel = pixels[y * IMAGE_SIZE + x]
                val r = (Color.red(pixel) / 255.0f - MEAN[0]) / STD[0]
                val g = (Color.green(pixel) / 255.0f - MEAN[1]) / STD[1]
                val b = (Color.blue(pixel) / 255.0f - MEAN[2]) / STD[2]

                input[0][y][x][0] = r
                input[0][y][x][1] = g
                input[0][y][x][2] = b
            }
        }

        if (resized != bitmap) {
            resized.recycle()
        }

        return input
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
