package com.augt.localseek.eval

import android.content.Context
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.search.RetrieverKind
import com.augt.localseek.search.RetrieverOutcome
import com.augt.localseek.search.SearchEngine
import com.augt.localseek.search.SearchOutcome
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BenchmarkRunnerTest {

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val searchEngine = mockk<SearchEngine>()

    private lateinit var benchmarkRunner: BenchmarkRunner

    private fun sampleFileResult(id: Long, score: Double) = FileResult(
        id = id,
        filePath = "/storage/file_$id.txt",
        title = "File $id",
        fileType = "txt",
        bestScore = score,
        snippets = listOf("Snippet for file $id"),
        modifiedAt = 1000L,
        sizeBytes = 250L,
        entityType = EntityType.FILE
    )

    @Before
    fun setup() {
        mockkObject(BenchmarkLogger)
        coEvery { BenchmarkLogger.logRun(any(), any()) } returns Unit

        benchmarkRunner = BenchmarkRunner(
            context = context,
            searchEngine = searchEngine,
            database = database
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `skipped dense retriever NEVER produces a benchmark row for dense backend (NEW-26 fix)`() = runBlocking {
        val query = "quantum computing"
        val sessionId = "session-test-new26"

        // SearchEngine returns Skipped for DENSE
        val skippedDenseOutcome = SearchOutcome(
            query = query,
            config = RetrievalConfig.DENSE_LSH,
            results = emptyList(),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Skipped("Disabled by config"),
                RetrieverKind.DENSE to RetrieverOutcome.Skipped("Dense index not initialized or skipped"),
                RetrieverKind.IMAGE to RetrieverOutcome.Skipped("Disabled by config")
            ),
            totalLatencyMs = 2L
        )

        coEvery { searchEngine.search(query, RetrievalConfig.DENSE_LSH) } returns skippedDenseOutcome

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(RetrievalConfig.DENSE_LSH)
        )

        // Must NOT produce any record or log any run for dense_lsh
        assertTrue("Records must be empty when dense retriever was skipped", records.isEmpty())
        coVerify(exactly = 0) { BenchmarkLogger.logRun(any(), any()) }
    }

    @Test
    fun `skipped BM25 retriever NEVER produces a benchmark row for bm25 backend`() = runBlocking {
        val query = "machine learning"
        val sessionId = "session-test-bm25-skip"

        val skippedBm25Outcome = SearchOutcome(
            query = query,
            config = RetrievalConfig.BM25,
            results = emptyList(),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Skipped("Disabled by config"),
                RetrieverKind.DENSE to RetrieverOutcome.Skipped("Disabled by config"),
                RetrieverKind.IMAGE to RetrieverOutcome.Skipped("Disabled by config")
            ),
            totalLatencyMs = 1L
        )

        coEvery { searchEngine.search(query, RetrievalConfig.BM25) } returns skippedBm25Outcome

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(RetrievalConfig.BM25)
        )

        assertTrue("Records must be empty when BM25 retriever was skipped", records.isEmpty())
        coVerify(exactly = 0) { BenchmarkLogger.logRun(any(), any()) }
    }

    @Test
    fun `logged resultIds and resultScores are byte-identical to direct SearchEngine outcome`() = runBlocking {
        val query = "deep learning research"
        val sessionId = "session-byte-identical"
        val config = RetrievalConfig.HYBRID_GLOBAL

        val results = listOf(
            sampleFileResult(101, 0.98),
            sampleFileResult(102, 0.85),
            sampleFileResult(103, 0.72)
        )

        val outcome = SearchOutcome(
            query = query,
            config = config,
            results = results,
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 15L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 25L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 10L)
            ),
            totalLatencyMs = 60L,
            bm25LatencyMs = 15L,
            denseLatencyMs = 25L,
            fusionLatencyMs = 10L,
            rerankLatencyMs = 10L
        )

        coEvery { searchEngine.search(query, config) } returns outcome

        val capturedRecord = slot<BenchmarkRunEntity>()
        coEvery { BenchmarkLogger.logRun(any(), capture(capturedRecord)) } returns Unit

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(config)
        )

        assertEquals(1, records.size)
        assertTrue(capturedRecord.isCaptured)

        val logged = capturedRecord.captured
        val expectedResultIdsJson = JSONArray(results.map { "${it.entityType}:${it.id}" }).toString()
        val expectedResultScoresJson = JSONArray(results.map { it.bestScore }).toString()
        val expectedResultTitlesJson = JSONArray(results.map { it.title }).toString()
        val expectedResultSnippetsJson = JSONArray(results.map { it.snippets.firstOrNull().orEmpty() }).toString()

        // Strict byte-level identity verification
        assertEquals("resultIdsJson must be byte-identical", expectedResultIdsJson, logged.resultIdsJson)
        assertEquals("resultScoresJson must be byte-identical", expectedResultScoresJson, logged.resultScoresJson)
        assertEquals("resultTitlesJson must be byte-identical", expectedResultTitlesJson, logged.resultTitlesJson)
        assertEquals("resultSnippetsJson must be byte-identical", expectedResultSnippetsJson, logged.resultSnippetsJson)

        assertEquals("hybrid_global", logged.backend)
        assertEquals(sessionId, logged.runSessionId)
        assertEquals(query, logged.queryText)
        assertEquals(60L, logged.latencyTotalMs)
        assertEquals(15L, logged.latencyBm25Ms)
        assertEquals(25L, logged.latencyDenseMs)
        assertEquals(10L, logged.latencyFusionMs)
        assertEquals(10L, logged.latencyRerankMs)
    }

    @Test
    fun `canonical benchmark suite drives all 12 canonical arms through SearchEngine search`() = runBlocking {
        val query = "neural networks"
        val sessionId = "session-full-suite"

        coEvery { searchEngine.search(query, any()) } answers {
            val cfg = secondArg<RetrievalConfig>()
            SearchOutcome(
                query = query,
                config = cfg,
                results = listOf(sampleFileResult(1, 0.9)),
                candidatesByRetriever = mapOf(
                    RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                    RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 20L),
                    RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 5L)
                ),
                totalLatencyMs = 35L
            )
        }

        val records = benchmarkRunner.runBenchmarkSuite(query, sessionId)

        assertEquals(12, records.size)
        val loggedBackends = records.map { it.backend }
        assertEquals(
            listOf(
                "E1_bm25", "E2_dense_exact", "E3_dense_lsh", "E4_hybrid_linear", "E5_hybrid_rrf",
                "E6_fusion_per_type", "E6_fusion_threshold", "E7_linear_reranked", "E7_rrf_reranked",
                "E7_linear_rerank20", "E7_rrf_rerank20", "E8_legacy"
            ),
            loggedBackends
        )

        // Verify SearchEngine.search was invoked exactly once for each of the 12 configs
        coVerify(exactly = 12) { searchEngine.search(query, any()) }

        // Verify properties of E7 full rerank vs E7 top-20 rerank
        val e7LinearFull = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == "E7_linear_reranked" }
        assertEquals(100, e7LinearFull.rerankTopK)
        assertEquals(120000L, e7LinearFull.maxRerankTimeMs)

        val e7RrfFull = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == "E7_rrf_reranked" }
        assertEquals(100, e7RrfFull.rerankTopK)
        assertEquals(120000L, e7RrfFull.maxRerankTimeMs)

        val e7Linear20 = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == "E7_linear_rerank20" }
        assertEquals(20, e7Linear20.rerankTopK)
        assertEquals(120000L, e7Linear20.maxRerankTimeMs)

        val e7Rrf20 = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == "E7_rrf_rerank20" }
        assertEquals(20, e7Rrf20.rerankTopK)
        assertEquals(120000L, e7Rrf20.maxRerankTimeMs)

        val e8Legacy = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == "E8_legacy" }
        assertEquals(120000L, e8Legacy.maxRerankTimeMs)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy 6-arm suite drives 6 arms when explicitly passed`() = runBlocking {
        val query = "neural networks"
        val sessionId = "session-legacy-suite"

        coEvery { searchEngine.search(query, any()) } answers {
            val cfg = secondArg<RetrievalConfig>()
            SearchOutcome(
                query = query,
                config = cfg,
                results = listOf(sampleFileResult(1, 0.9)),
                candidatesByRetriever = mapOf(
                    RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                    RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 20L),
                    RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 5L)
                ),
                totalLatencyMs = 35L
            )
        }

        val records = benchmarkRunner.runBenchmarkSuite(query, sessionId, BenchmarkRunner.STANDARD_BENCHMARK_CONFIGS)

        assertEquals(6, records.size)
        val loggedBackends = records.map { it.backend }
        assertEquals(
            listOf("bm25", "dense_lsh", "dense_bruteforce", "hybrid_global", "hybrid_per_type", "hybrid_threshold"),
            loggedBackends
        )
        coVerify(atLeast = 6) { searchEngine.search(query, any()) }
    }

    @Test
    fun `legacy preset directly reports rerank latency without backend-name filtering`() = runBlocking {
        val query = "quarterly projection"
        val sessionId = "session-legacy-latency"

        val legacyOutcome = SearchOutcome(
            query = query,
            config = RetrievalConfig.LEGACY,
            results = listOf(sampleFileResult(1, 0.95)),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 15L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 35L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 10L)
            ),
            totalLatencyMs = 820L,
            bm25LatencyMs = 15L,
            denseLatencyMs = 35L,
            fusionLatencyMs = 10L,
            rerankLatencyMs = 715L
        )
        coEvery { searchEngine.search(query, RetrievalConfig.LEGACY) } returns legacyOutcome

        val records = benchmarkRunner.runBenchmarkSuite(query, sessionId, listOf(RetrievalConfig.LEGACY))

        assertEquals(1, records.size)
        val logged = records.first()
        assertEquals("legacy", logged.backend)
        assertEquals(715L, logged.latencyRerankMs)
        assertEquals(820L, logged.latencyTotalMs)
    }

    @Test
    fun `cross-encoder score cache is cleared once per benchmark configuration`() = runBlocking {
        val query = "neural query"
        val sessionId = "session-cache-clear"
        val mockReranker = mockk<com.augt.localseek.retrieval.CrossEncoderReranker>(relaxed = true)

        val runnerWithReranker = BenchmarkRunner(
            context = context,
            searchEngine = searchEngine,
            database = database,
            crossEncoderReranker = mockReranker
        )

        coEvery { searchEngine.search(query, any()) } answers {
            SearchOutcome(
                query = query,
                config = secondArg(),
                results = listOf(sampleFileResult(1, 0.8)),
                candidatesByRetriever = mapOf(
                    RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 5L),
                    RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 5L),
                    RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 5L)
                ),
                totalLatencyMs = 20L
            )
        }

        val testConfigs = listOf(
            RetrievalConfig.BM25,
            RetrievalConfig.HYBRID_GLOBAL,
            RetrievalConfig.CLEAN
        )

        runnerWithReranker.runBenchmarkSuite(query, sessionId, testConfigs)

        // Must be cleared once per configuration
        io.mockk.verify(exactly = 3) { mockReranker.clearCache() }
    }

    @Test
    fun `timed-out reranking operation is skipped and never recorded as a successful benchmark row`() = runBlocking {
        val query = "timeout query"
        val sessionId = "session-timeout"

        val timedOutOutcome = SearchOutcome(
            query = query,
            config = RetrievalConfig.CLEAN,
            results = listOf(sampleFileResult(1, 0.8)),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 20L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 5L)
            ),
            totalLatencyMs = 5050L,
            rerankLatencyMs = 5000L,
            rerankTimedOut = true
        )
        coEvery { searchEngine.search(query, RetrievalConfig.CLEAN) } returns timedOutOutcome

        val records = benchmarkRunner.runBenchmarkSuite(query, sessionId, listOf(RetrievalConfig.CLEAN))

        assertTrue("Benchmark runner must skip timed-out rerank runs", records.isEmpty())
        coVerify(exactly = 0) { BenchmarkLogger.logRun(any(), match { it.backend == "clean" }) }

        // Exclusion accounting verification
        assertEquals(1, benchmarkRunner.lastExclusions.size)
        val exclusion = benchmarkRunner.lastExclusions.first()
        assertTrue("Exclusion must be marked as timeout", exclusion.isTimeout)
        assertEquals("clean", exclusion.backend)
        assertEquals(query, exclusion.query)
    }

    @Test
    fun `runBenchmarkSuite sets repetitionIndex on entity`() = runBlocking {
        val query = "query"
        val sessionId = "session-rep"
        val outcome = SearchOutcome(
            query = query,
            config = RetrievalConfig.BM25,
            results = listOf(sampleFileResult(1, 0.9)),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 10L)
            ),
            totalLatencyMs = 30L
        )
        coEvery { searchEngine.search(query, RetrievalConfig.BM25) } returns outcome

        val captured = slot<BenchmarkRunEntity>()
        coEvery { BenchmarkLogger.logRun(any(), capture(captured)) } returns Unit

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(RetrievalConfig.BM25),
            repetitionIndex = 4
        )

        assertEquals(1, records.size)
        assertEquals(4, captured.captured.repetitionIndex)
        assertEquals(4, records.first().repetitionIndex)
    }

    @Test
    fun `rerank latency exceeding 500ms budget sets rerankTimedOut true`() = runBlocking {
        val query = "query"
        val sessionId = "session-budget"
        val config = RetrievalConfig.CLEAN
        val outcome = SearchOutcome(
            query = query,
            config = config,
            results = listOf(sampleFileResult(1, 0.9)),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 10L)
            ),
            totalLatencyMs = 650L,
            rerankLatencyMs = 550L,
            rerankTimedOut = false
        )
        coEvery { searchEngine.search(query, config) } returns outcome

        val captured = slot<BenchmarkRunEntity>()
        coEvery { BenchmarkLogger.logRun(any(), capture(captured)) } returns Unit

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(config)
        )

        assertEquals(1, records.size)
        assertTrue("550ms rerank latency must set rerankTimedOut to true", captured.captured.rerankTimedOut)
    }

    @Test
    fun `returned entity types violating config marks run invalid`() = runBlocking {
        val query = "query"
        val sessionId = "session-invalid"
        val config = RetrievalConfig.BM25.copy(enableImage = false)
        val imageResult = FileResult(
            id = 99,
            title = "Photo",
            snippets = listOf("image snippet"),
            filePath = "/sdcard/photo.jpg",
            fileType = "jpg",
            bestScore = 0.9,
            modifiedAt = 1000L,
            sizeBytes = 100L,
            entityType = com.augt.localseek.model.EntityType.IMAGE
        )
        val outcome = SearchOutcome(
            query = query,
            config = config,
            results = listOf(imageResult),
            candidatesByRetriever = mapOf(
                RetrieverKind.BM25 to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.DENSE to RetrieverOutcome.Ran(emptyList(), 10L),
                RetrieverKind.IMAGE to RetrieverOutcome.Ran(emptyList(), 10L)
            ),
            totalLatencyMs = 30L
        )
        coEvery { searchEngine.search(query, config) } returns outcome

        val captured = slot<BenchmarkRunEntity>()
        coEvery { BenchmarkLogger.logRun(any(), capture(captured)) } returns Unit

        val records = benchmarkRunner.runBenchmarkSuite(
            query = query,
            runSessionId = sessionId,
            configs = listOf(config)
        )

        assertEquals(1, records.size)
        assertEquals("Run with IMAGE when enableImage=false must have isValid=false", false, captured.captured.isValid)
        assertEquals(false, records.first().isValid)
    }
}
