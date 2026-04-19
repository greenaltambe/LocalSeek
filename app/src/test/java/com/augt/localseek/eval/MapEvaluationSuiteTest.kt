package com.augt.localseek.eval

import com.augt.localseek.retrieval.FileResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEvaluationSuiteTest {

    @Test
    fun mapEvaluationProducesExpectedRange() = runBlocking {
        val fixtures = mapOf(
            "kotlin coroutines" to listOf(
                file("/docs/kotlin_guide.pdf", 0.95),
                file("/docs/random.txt", 0.40)
            ),
            "machine learning" to listOf(
                file("/docs/ml_intro.pdf", 0.91),
                file("/docs/neural_nets.txt", 0.88),
                file("/docs/other.md", 0.20)
            ),
            "android workmanager" to listOf(
                file("/docs/workmanager_notes.md", 0.85)
            )
        )

        val engine = object : EvaluationSearchEngine {
            override suspend fun search(query: String): List<FileResult> = fixtures[query].orEmpty()
        }

        val map = EvaluationSuite.evaluateMAP(engine)
        assertTrue("Expected MAP > 0.7 but got $map", map > 0.7)
    }

    private fun file(path: String, score: Double): FileResult {
        val title = path.substringAfterLast('/')
        return FileResult(
            id = title.hashCode().toLong(),
            filePath = path,
            title = title,
            fileType = title.substringAfterLast('.', "txt"),
            bestScore = score,
            snippets = listOf("snippet"),
            modifiedAt = System.currentTimeMillis(),
            sizeBytes = 1024
        )
    }
}


