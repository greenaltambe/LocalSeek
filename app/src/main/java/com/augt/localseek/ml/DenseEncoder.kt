package com.augt.localseek.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class DenseEncoder(context: Context) {

    private val tokenizer = BertTokenizer(context)
    private var interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context, "minilm_int8.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(modelBuffer, options)
        
        // Log model info for debugging
        for (i in 0 until interpreter.inputTensorCount) {
            val tensor = interpreter.getInputTensor(i)
            Log.d("DenseEncoder", "Input $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
        }
        for (i in 0 until interpreter.outputTensorCount) {
            val tensor = interpreter.getOutputTensor(i)
            Log.d("DenseEncoder", "Output $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
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
     * Converts a piece of text into a 384-dimensional semantic vector embedding.
     */
    fun encode(text: String): FloatArray {
        // 1. Tokenize the text
        val (inputIds, attentionMask) = tokenizer.tokenize(text, 256)
        val tokenTypeIds = IntArray(256) { 0 } // Standard for single-sentence BERT

        // 2. Prepare inputs matching most BERT TFLite models [1, 256]
        // We provide 3 inputs as many MiniLM models require input_ids, attention_mask, and segment_ids
        val inputs = arrayOf<Any>(
            Array(1) { inputIds },
            Array(1) { attentionMask },
            Array(1) { tokenTypeIds }
        )

        // 3. Prepare output buffer ([1, 384])
        // We assume the model has a pooling layer. If not, shape would be [1, 256, 384]
        val outputArray = Array(1) { FloatArray(384) }
        val outputs = mutableMapOf<Int, Any>(0 to outputArray)

        // 4. Run the Neural Network
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            Log.e("DenseEncoder", "Inference failed: ${e.message}")
            // Fallback for models with 2 inputs
            if (e.message?.contains("input") == true) {
                val inputs2 = arrayOf<Any>(Array(1) { inputIds }, Array(1) { attentionMask })
                interpreter.runForMultipleInputsOutputs(inputs2, outputs)
            }
        }

        val result = outputArray[0]
        
        // Check if output is all zeros
        if (result.all { it == 0.0f }) {
            Log.w("DenseEncoder", "Warning: Encoder returned an all-zero vector for text: $text")
        }

        return result
    }

    fun close() {
        interpreter.close()
    }
}
