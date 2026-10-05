package com.augt.localseek.search

import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.CrossEncoderReranker
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.ImageRetriever
import com.augt.localseek.search.query.QueryProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchEngineTest {

    private val bm25Retriever = mockk<BM25Retriever>()
    private val denseRetriever = mockk<DenseRetriever>()
    private val exactDenseRetriever = mockk<DenseRetriever>()
    private val imageRetriever = mockk<ImageRetriever>()
    private val crossEncoderReranker = mockk<CrossEncoderReranker>()
    private val queryProcessor = mockk<QueryProcessor>()

    private lateinit var searchEngine: SearchEngine

    private fun sampleResult(id: Long, score: Float, entityType: EntityType = EntityType.FILE) = SearchResult(
        id = id,
        title = "Title $id",
        snippet = "Snippet $id",
        filePath = "/storage/file_$id.txt",
        fileType = "txt",
        score = score,
        modifiedAt = 1000L,
        sizeBytes = 100L,
        entityType = entityType
    )

    private fun mockProcessedQuery(raw: String): QueryProcessor.ProcessedQuery {
        val processed = mockk<QueryProcessor.ProcessedQuery>(relaxed = true)
        every { processed.original } returns raw
        every { processed.normalized.normalized } returns raw.lowercase().trim()
        every { processed.bm25Query } returns raw
        every { processed.denseQuery } returns raw
        return processed
    }

    @Before
    fun setup() {
        val dummyVectorIndex = mockk<com.augt.localseek.search.vector.VectorIndex>(relaxed = true)
        every { denseRetriever.getVectorIndex() } returns dummyVectorIndex
        every { exactDenseRetriever.getVectorIndex() } returns dummyVectorIndex
        coEvery { crossEncoderReranker.rerank(any(), any(), any(), any(), any(), any(), any(), any()) } answers { secondArg() }
        coEvery { crossEncoderReranker.rerankDetailed(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CrossEncoderReranker.RerankOutcome(secondArg(), timedOut = false)
        }

        searchEngine = SearchEngine(
            bm25Retriever = bm25Retriever,
            denseRetriever = denseRetriever,
            exactDenseRetriever = exactDenseRetriever,
            imageRetriever = imageRetriever,
            crossEncoderReranker = crossEncoderReranker,
            queryProcessor = queryProcessor
        )
    }

    @Test
    fun `search with blank query returns empty results and all retrievers Skipped`() = runBlocking {
        val outcome = searchEngine.search("", RetrievalConfig.DEFAULT)

        assertTrue(outcome.results.isEmpty())
        assertEquals(3, outcome.candidatesByRetriever.size)
        assertTrue(outcome.candidatesByRetriever[RetrieverKind.BM25] is RetrieverOutcome.Skipped)
        assertTrue(outcome.candidatesByRetriever[RetrieverKind.DENSE] is RetrieverOutcome.Skipped)
        assertTrue(outcome.candidatesByRetriever[RetrieverKind.IMAGE] is RetrieverOutcome.Skipped)
        assertEquals(0L, outcome.totalLatencyMs)
    }

    @Test
    fun `BM25-only arm queries only BM25 and marks Dense and Image Skipped`() = runBlocking {
        val query = "quarterly report"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        coEvery { bm25Retriever.search(any(), any()) } returns listOf(sampleResult(1, 0.85f))

        val outcome = searchEngine.search(query, RetrievalConfig.BM25)

        assertEquals(1, outcome.results.size)
        assertEquals(1L, outcome.results[0].id)

        val bm25Outcome = outcome.candidatesByRetriever[RetrieverKind.BM25]
        val denseOutcome = outcome.candidatesByRetriever[RetrieverKind.DENSE]
        val imageOutcome = outcome.candidatesByRetriever[RetrieverKind.IMAGE]

        assertTrue(bm25Outcome is RetrieverOutcome.Ran)
        assertEquals(1, (bm25Outcome as RetrieverOutcome.Ran).candidates.size)

        assertTrue(denseOutcome is RetrieverOutcome.Skipped)
        assertEquals("Disabled by config", (denseOutcome as RetrieverOutcome.Skipped).reason)

        assertTrue(imageOutcome is RetrieverOutcome.Skipped)
        assertEquals("Disabled by config", (imageOutcome as RetrieverOutcome.Skipped).reason)

        coVerify(exactly = 1) { bm25Retriever.search(any(), any()) }
        coVerify(exactly = 0) { denseRetriever.search(any(), any()) }
        coVerify(exactly = 0) { imageRetriever.search(any(), any(), any()) }
    }

    @Test
    fun `Dense-only arm with LSH queries standard denseRetriever`() = runBlocking {
        val query = "project architecture"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { denseRetriever.search(any(), any()) } returns listOf(sampleResult(10, 0.92f))

        val outcome = searchEngine.search(query, RetrievalConfig.DENSE_LSH)

        assertEquals(1, outcome.results.size)
        assertEquals(10L, outcome.results[0].id)

        assertTrue(outcome.candidatesByRetriever[RetrieverKind.BM25] is RetrieverOutcome.Skipped)
        assertTrue(outcome.candidatesByRetriever[RetrieverKind.DENSE] is RetrieverOutcome.Ran)
        assertTrue(outcome.candidatesByRetriever[RetrieverKind.IMAGE] is RetrieverOutcome.Skipped)

        coVerify(exactly = 1) { denseRetriever.search(any(), any()) }
        coVerify(exactly = 0) { exactDenseRetriever.search(any(), any()) }
    }

    @Test
    fun `Dense-only arm with EXACT queries exactDenseRetriever without index mutation`() = runBlocking {
        val query = "machine learning notes"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { exactDenseRetriever.search(any(), any()) } returns listOf(sampleResult(20, 0.95f))

        val outcome = searchEngine.search(query, RetrievalConfig.DENSE_BRUTE_FORCE)

        assertEquals(1, outcome.results.size)
        assertEquals(20L, outcome.results[0].id)

        assertTrue(outcome.candidatesByRetriever[RetrieverKind.DENSE] is RetrieverOutcome.Ran)

        coVerify(exactly = 1) { exactDenseRetriever.search(any(), any()) }
        coVerify(exactly = 0) { denseRetriever.search(any(), any()) }
    }

    @Test
    fun `dense skip threshold triggers RetrieverOutcome Skipped when BM25 score is high`() = runBlocking {
        val query = "exact filename.pdf"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        val bm25Candidate = sampleResult(5, 0.95f)
        coEvery { bm25Retriever.search(any(), any()) } returns listOf(bm25Candidate)
        coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()
        coEvery { crossEncoderReranker.rerank(any(), any()) } answers { secondArg() }

        // Dense retriever reports skip condition met
        every { denseRetriever.shouldSkipDense(listOf(bm25Candidate), any()) } returns true

        val outcome = searchEngine.search(query, RetrievalConfig.DEFAULT)

        val denseOutcome = outcome.candidatesByRetriever[RetrieverKind.DENSE]
        assertTrue(denseOutcome is RetrieverOutcome.Skipped)
        assertTrue((denseOutcome as RetrieverOutcome.Skipped).reason.contains("skip threshold reached"))

        coVerify(exactly = 0) { denseRetriever.search(any(), any()) }
    }

    @Test
    fun `benchmark mode never skips dense retrieval even if shouldSkipDense is true`() = runBlocking {
        val query = "exact filename.pdf"
        coEvery { queryProcessor.process(query, any()) } returns mockProcessedQuery(query)
        val bm25Candidate = sampleResult(5, 0.95f)
        coEvery { bm25Retriever.search(any(), any(), any()) } returns listOf(bm25Candidate)
        coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()
        every { denseRetriever.shouldSkipDense(listOf(bm25Candidate), any()) } returns true
        coEvery { denseRetriever.search(any(), any()) } returns listOf(sampleResult(6, 0.88f))

        val benchmarkConfig = RetrievalConfig.HYBRID_GLOBAL
        val outcome = searchEngine.search(query, benchmarkConfig)

        val denseOutcome = outcome.candidatesByRetriever[RetrieverKind.DENSE]
        assertTrue("Dense outcome must have run in benchmark mode", denseOutcome is RetrieverOutcome.Ran)
        coVerify(atLeast = 1) { denseRetriever.search(any(), any()) }
    }

    @Test
    fun `retriever exception produces RetrieverOutcome Failed without throwing`() = runBlocking {
        val query = "failing test"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        coEvery { bm25Retriever.search(any(), any()) } throws RuntimeException("SQLite disk I/O error")
        coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { denseRetriever.search(any(), any()) } returns emptyList()
        coEvery { crossEncoderReranker.rerank(any(), any()) } returns emptyList()

        val outcome = searchEngine.search(query, RetrievalConfig.DEFAULT)

        val bm25Outcome = outcome.candidatesByRetriever[RetrieverKind.BM25]
        assertTrue(bm25Outcome is RetrieverOutcome.Failed)
        assertEquals("SQLite disk I/O error", (bm25Outcome as RetrieverOutcome.Failed).cause)
    }

    @Test
    fun `rerankBeforeDiversify passes full depth to reranker in CLEAN mode vs truncated in LEGACY mode`() = runBlocking {
        val query = "evaluation benchmark"
        coEvery { queryProcessor.process(query, any()) } returns mockProcessedQuery(query)

        // 30 BM25 and 30 Dense candidates
        val bm25List = (1..30).map { sampleResult(it.toLong(), 0.8f) }
        val denseList = (1..30).map { sampleResult(it.toLong(), 0.8f) }
        coEvery { bm25Retriever.search(any(), any()) } returns bm25List
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { denseRetriever.search(any(), any()) } returns denseList
        coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()

        var capturedCandidatesCount = 0
        coEvery {
            crossEncoderReranker.rerankDetailed(
                query = any(),
                candidates = any(),
                topK = any(),
                returnTopK = any(),
                crossWeight = any(),
                initialWeight = any(),
                tokenizerMode = any(),
                maxRerankTimeMs = any()
            )
        } answers {
            val list = secondArg<List<SearchResult>>()
            capturedCandidatesCount = list.size
            CrossEncoderReranker.RerankOutcome(list, timedOut = false)
        }

        // 1. CLEAN config has rerankBeforeDiversify = true, rerankTopK = 50, returnTopK = 10
        val cleanConfig = RetrievalConfig.CLEAN.copy(
            enableRerank = true,
            rerankTopK = 50,
            returnTopK = 10
        )
        searchEngine.search(query, cleanConfig)
        assertEquals("CLEAN mode passes full rerankTopK depth (30 items) to reranker", 30, capturedCandidatesCount)

        // 2. LEGACY config has rerankBeforeDiversify = false, returnTopK = 10
        val legacyConfig = RetrievalConfig.LEGACY.copy(
            enableRerank = true,
            rerankTopK = 50,
            returnTopK = 10
        )
        searchEngine.search(query, legacyConfig)
        assertEquals("LEGACY mode truncates to 20 before reranker runs", 20, capturedCandidatesCount)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `cancelled search rethrows CancellationException without returning partial results`() {
        runBlocking {
            val query = "cancelled query"
            coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
            coEvery { bm25Retriever.search(any(), any()) } throws kotlinx.coroutines.CancellationException("Job cancelled")
            coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()
            every { denseRetriever.shouldSkipDense(any(), any()) } returns false
            coEvery { denseRetriever.search(any(), any()) } returns emptyList()

            searchEngine.search(query, RetrievalConfig.DEFAULT)
        }
    }

    @Test
    fun `SearchEngine records RetrieverOutcome Failed when BM25Retriever FTS query throws`() = runBlocking {
        val query = "syntax error query"
        val mockContext = mockk<android.content.Context>(relaxed = true)
        val mockDb = mockk<com.augt.localseek.data.AppDatabase>()
        val chunkDao = mockk<com.augt.localseek.data.ChunkDao>()
        every { mockDb.chunkDao() } returns chunkDao
        every { mockDb.appDao() } returns mockk(relaxed = true)
        every { mockDb.contactDao() } returns mockk(relaxed = true)
        coEvery { chunkDao.searchChunks(any(), any()) } throws RuntimeException("fts5: syntax error")

        val realBm25Retriever = BM25Retriever(mockContext, mockDb)
        val engine = SearchEngine(
            bm25Retriever = realBm25Retriever,
            denseRetriever = denseRetriever,
            exactDenseRetriever = exactDenseRetriever,
            imageRetriever = imageRetriever,
            crossEncoderReranker = crossEncoderReranker,
            queryProcessor = queryProcessor
        )

        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)
        coEvery { imageRetriever.search(any(), any(), any()) } returns emptyList()
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { denseRetriever.search(any(), any()) } returns emptyList()

        val outcome = engine.search(query, RetrievalConfig.DEFAULT)
        val bm25Outcome = outcome.candidatesByRetriever[RetrieverKind.BM25]
        assertTrue("BM25 outcome must be Failed when FTS throws", bm25Outcome is RetrieverOutcome.Failed)
        assertEquals("fts5: syntax error", (bm25Outcome as RetrieverOutcome.Failed).cause)
    }

    @Test
    fun `entity types returned obey config with no IMAGE result when enableImage is false`() = runBlocking {
        val query = "family vacation"
        coEvery { queryProcessor.process(query) } returns mockProcessedQuery(query)

        val fileCandidate = sampleResult(1, 0.8f, EntityType.FILE)
        val appCandidate = sampleResult(2, 0.7f, EntityType.APP)
        val imageCandidate = sampleResult(3, 0.95f, EntityType.IMAGE)

        coEvery { bm25Retriever.search(any(), any()) } returns listOf(fileCandidate, appCandidate)
        every { denseRetriever.shouldSkipDense(any(), any()) } returns false
        coEvery { denseRetriever.search(any(), any()) } returns listOf(fileCandidate)
        coEvery { imageRetriever.search(any(), any(), any()) } returns listOf(imageCandidate)

        // 1. With enableImage = false
        val configNoImage = RetrievalConfig.DEFAULT.copy(enableImage = false)
        val outcomeNoImage = searchEngine.search(query, configNoImage)

        val imageOutcome = outcomeNoImage.candidatesByRetriever[RetrieverKind.IMAGE]
        assertTrue("ImageRetriever must be Skipped when enableImage=false", imageOutcome is RetrieverOutcome.Skipped)
        coVerify(exactly = 0) { imageRetriever.search(any(), any(), any()) }

        assertTrue("No IMAGE result must be returned when enableImage=false",
            outcomeNoImage.results.none { it.entityType == EntityType.IMAGE })
        assertTrue("Returned results must contain only enabled types (FILE, APP)",
            outcomeNoImage.results.all { it.entityType == EntityType.FILE || it.entityType == EntityType.APP })

        // 2. With enableImage = true
        val configWithImage = RetrievalConfig.DEFAULT.copy(enableImage = true)
        val outcomeWithImage = searchEngine.search(query, configWithImage)

        val imageOutcomeEnabled = outcomeWithImage.candidatesByRetriever[RetrieverKind.IMAGE]
        assertTrue("ImageRetriever must be Ran when enableImage=true", imageOutcomeEnabled is RetrieverOutcome.Ran)
        coVerify(atLeast = 1) { imageRetriever.search(any(), any(), any()) }
        assertTrue("Results must contain IMAGE when enableImage=true",
            outcomeWithImage.results.any { it.entityType == EntityType.IMAGE })
    }
}
