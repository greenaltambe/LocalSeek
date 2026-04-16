package com.augt.localseek.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DenseEncoder(context: Context) {

    private val tokenizer = BertTokenizer(context)
    private var interpreter: Interpreter

    init {
        // Load the TFLite model from assets
        val modelBuffer = loadModelFile(context, "minilm_int8.tflite")

        // Setup TFLite Options (e.g., Use 4 threads for faster CPU execution)
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
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
     * Converts a piece of text into a 384-dimensional semantic vector embedding.
     */
    fun encode(text: String): FloatArray {
        // 1. Tokenize the text
        val (inputIds, attentionMask) = tokenizer.tokenize(text, 256)

        // 2. Prepare inputs matching Python tf.function signature ([1, 256])
        val inputArray0 = Array(1) { inputIds }
        val inputArray1 = Array(1) { attentionMask }

        // TFLite requires inputs as an Object array
        val inputs = arrayOf<Any>(inputArray0, inputArray1)

        // 3. Prepare output buffer ([1, 384])
        val outputArray = Array(1) { FloatArray(384) }
        val outputs = mutableMapOf<Int, Any>(0 to outputArray)

        // 4. Run the Neural Network
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        // Return the 384-float vector
        return outputArray[0]
    }

    fun close() {
        interpreter.close()
    }
}