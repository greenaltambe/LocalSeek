package com.augt.localseek.eval

import com.augt.localseek.retrieval.FileResult

data class TestQuery(
    val query: String,
    val relevantDocs: Set<String>
)

interface EvaluationSearchEngine {
    suspend fun search(query: String): List<FileResult>
}

object EvaluationSuite {

    val testQueries = listOf(
        TestQuery("kotlin coroutines", setOf("/docs/kotlin_guide.pdf")),
        TestQuery("machine learning", setOf("/docs/ml_intro.pdf", "/docs/neural_nets.txt")),
        TestQuery("android workmanager", setOf("/docs/workmanager_notes.md"))
    )

    suspend fun evaluateMAP(
        engine: EvaluationSearchEngine,
        queries: List<TestQuery> = testQueries
    ): Double {
        if (queries.isEmpty()) return 0.0

        var totalAP = 0.0
        queries.forEach { test ->
            val results = engine.search(test.query)
            totalAP += calculateAP(results, test.relevantDocs)
        }

        return totalAP / queries.size
    }

    fun calculateAP(results: List<FileResult>, relevant: Set<String>): Double {
        if (relevant.isEmpty()) return 0.0

        var relevantCount = 0
        var sumPrecision = 0.0

        results.forEachIndexed { index, result ->
            if (result.filePath in relevant) {
                relevantCount++
                sumPrecision += relevantCount.toDouble() / (index + 1)
            }
        }

        return if (relevantCount > 0) sumPrecision / relevant.size else 0.0
    }
}

