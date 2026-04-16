package com.augt.localseek.ranking

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.HybridResult
import java.nio.FloatBuffer
import java.util.Collections

class LambdaMARTReranker(context: Context) {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("reranker.onnx").readBytes()
        session = ortEnvironment.createSession(modelBytes)
    }

    fun rerank(candidates: List<HybridResult>): List<SearchResult> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Feature Engineering: Create the input tensor for the ONNX model
        val features = candidates.map {
            val daysSinceModified = ((System.currentTimeMillis() - it.result.modifiedAt) / (1000 * 60 * 60 * 24)).toFloat()
            // Feature order MUST match the Python training script!
            floatArrayOf(it.bm25Score, it.denseScore, daysSinceModified)
        }.toTypedArray()

        val numRows = candidates.size.toLong()
        val numCols = 3L
        val inputBuffer = FloatBuffer.wrap(features.flatMap { it.toList() }.toFloatArray())
        val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, longArrayOf(numRows, numCols))

        // 2. Run Inference
        val inputs = Collections.singletonMap(session.inputNames.iterator().next(), inputTensor)
        val results = session.run(inputs)
        val outputScores = (results[0].value as Array<FloatArray>).map { it[0] }

        // 3. Combine original documents with new scores and re-sort
        return candidates.zip(outputScores)
            .sortedByDescending { it.second }
            .map { (hybridResult, newScore) ->
                hybridResult.result.copy(score = newScore)
            }
    }
}
