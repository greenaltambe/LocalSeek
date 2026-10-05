package com.augt.localseek.retrieval

import android.content.Context
import android.util.LruCache
import com.augt.localseek.ml.CrossEncoder
import com.augt.localseek.ml.TokenizerMode
import com.augt.localseek.model.EntityType
import com.augt.localseek.model.SearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrossEncoderRerankerTest {

    private val context = mockk<Context>(relaxed = true)
    private val crossEncoder = mockk<CrossEncoder>()
    private lateinit var reranker: CrossEncoderReranker
    private val cacheMap = mutableMapOf<String, Float>()

    private fun sampleCandidate(id: Long, score: Float, stableKey: String = "doc_$id"): SearchResult = SearchResult(
        id = id,
        title = "Title $id",
        snippet = "Snippet $id",
        filePath = "/path/to/$id.txt",
        fileType = "txt",
        score = score,
        modifiedAt = 1000L,
        entityType = EntityType.FILE,
        stableKey = stableKey
    )

    @Before
    fun setup() {
        mockkConstructor(LruCache::class)
        every { anyConstructed<LruCache<String, Float>>().get(any()) } answers { cacheMap[firstArg()] }
        every { anyConstructed<LruCache<String, Float>>().put(any(), any()) } answers { cacheMap.put(firstArg(), secondArg()) }
        every { anyConstructed<LruCache<String, Float>>().evictAll() } answers { cacheMap.clear() }

        every { crossEncoder.isAvailable } returns true
        reranker = CrossEncoderReranker(context, crossEncoder, ownsResources = false)
    }

    @After
    fun tearDown() {
        unmockkConstructor(LruCache::class)
        cacheMap.clear()
    }

    @Test
    fun `score cache caches inference and clearCache forces re-evaluation`() = runBlocking {
        val query = "quantum computing"
        val candidate = sampleCandidate(1, 0.5f, "doc_1")

        coEvery { crossEncoder.score(query, candidate.snippet, TokenizerMode.FIXED) } returns 0.9f

        // First rerank call: cache miss, invokes crossEncoder.score
        val firstResult = reranker.rerank(query, listOf(candidate))
        assertEquals(1, firstResult.size)
        // Hybrid score: 0.7 * sigmoid(0.9) + 0.3 * 0.5 = 0.7 * 0.71095 + 0.15 = 0.64766
        val expectedScore = 0.7f * CrossEncoderReranker.sigmoid(0.9f) + 0.3f * 0.5f
        assertEquals(expectedScore, firstResult.first().score, 0.001f)
        coVerify(exactly = 1) { crossEncoder.score(query, candidate.snippet, TokenizerMode.FIXED) }

        // Second rerank call with same query and candidate: cache hit, crossEncoder.score NOT called again
        val secondResult = reranker.rerank(query, listOf(candidate))
        assertEquals(expectedScore, secondResult.first().score, 0.001f)
        coVerify(exactly = 1) { crossEncoder.score(query, candidate.snippet, TokenizerMode.FIXED) }

        // Clear cache
        reranker.clearCache()

        // Third rerank call: cache miss again, crossEncoder.score MUST be called a second time
        val thirdResult = reranker.rerank(query, listOf(candidate))
        assertEquals(expectedScore, thirdResult.first().score, 0.001f)
        coVerify(exactly = 2) { crossEncoder.score(query, candidate.snippet, TokenizerMode.FIXED) }
    }

    @Test
    fun `rerankDetailed flags timedOut true and returns original candidate ordering when timeout exceeded`() = runBlocking {
        val query = "slow query"
        val candidateA = sampleCandidate(1, 0.9f, "doc_1")
        val candidateB = sampleCandidate(2, 0.1f, "doc_2")

        // Mock inference taking longer than maxRerankTimeMs (delay 100ms when max timeout is 50ms)
        coEvery { crossEncoder.score(query, any(), any()) } coAnswers {
            delay(100) // Delay coroutine execution beyond 50ms
            0.99f
        }

        val outcome = reranker.rerankDetailed(
            query = query,
            candidates = listOf(candidateA, candidateB),
            maxRerankTimeMs = 50L
        )

        assertTrue("Outcome must indicate timeout", outcome.timedOut)
        assertEquals("Fallback must preserve candidate list size", 2, outcome.results.size)
        assertEquals("Fallback must preserve original order", candidateA.id, outcome.results[0].id)
        assertEquals("Fallback must preserve original order", candidateB.id, outcome.results[1].id)
    }

    @Test
    fun `rerankDetailed flags timedOut false and sorts by hybrid score when successful`() = runBlocking {
        val query = "fast query"
        // Initial ranking: candidateA has higher initial score (0.8 vs 0.2)
        val candidateA = sampleCandidate(1, 0.8f, "doc_1")
        val candidateB = sampleCandidate(2, 0.2f, "doc_2")

        // Cross-encoder scores: candidateB has much higher cross logit (4.0 vs -2.0)
        coEvery { crossEncoder.score(query, candidateA.snippet, TokenizerMode.FIXED) } returns -2.0f
        coEvery { crossEncoder.score(query, candidateB.snippet, TokenizerMode.FIXED) } returns 4.0f

        val outcome = reranker.rerankDetailed(
            query = query,
            candidates = listOf(candidateA, candidateB),
            maxRerankTimeMs = 5000L
        )

        assertFalse("Outcome must not be timed out", outcome.timedOut)
        assertEquals(2, outcome.results.size)

        val expectedB = 0.7f * CrossEncoderReranker.sigmoid(4.0f) + 0.3f * 0.0f
        val expectedA = 0.7f * CrossEncoderReranker.sigmoid(-2.0f) + 0.3f * 1.0f

        assertEquals(candidateB.id, outcome.results[0].id)
        assertEquals(expectedB, outcome.results[0].score, 0.001f)
        assertEquals(candidateA.id, outcome.results[1].id)
        assertEquals(expectedA, outcome.results[1].score, 0.001f)
    }

    @Test
    fun `sigmoid function maps logits to zero one range monotonically`() {
        assertEquals(0.5f, CrossEncoderReranker.sigmoid(0.0f), 0.0001f)
        assertTrue(CrossEncoderReranker.sigmoid(-10.0f) in 0.0f..0.0001f)
        assertTrue(CrossEncoderReranker.sigmoid(10.0f) in 0.9999f..1.0f)
        assertTrue(CrossEncoderReranker.sigmoid(1.0f) > CrossEncoderReranker.sigmoid(0.0f))
    }

    @Test
    fun `fused score is non-negligible under RRF and breaks ties on close cross-encoder logits`() = runBlocking {
        val query = "local search"

        // Candidate A has high RRF rank (top BM25 + top Dense = ~0.033)
        // Candidate B has lower RRF rank (lower BM25, no Dense = ~0.016)
        val rrfHigh = sampleCandidate(1, 0.033f, "doc_rrf_high")
        val rrfLow = sampleCandidate(2, 0.016f, "doc_rrf_low")

        // Cross-encoder gives both negative logits typical of non-passage snippets,
        // with Candidate B receiving a very slight logit edge (-2.45 vs -2.50).
        coEvery { crossEncoder.score(query, rrfHigh.snippet, TokenizerMode.FIXED) } returns -2.50f
        coEvery { crossEncoder.score(query, rrfLow.snippet, TokenizerMode.FIXED) } returns -2.45f

        val outcome = reranker.rerankDetailed(
            query = query,
            candidates = listOf(rrfHigh, rrfLow),
            maxRerankTimeMs = 5000L
        )

        // After min-max normalisation of fused scores across candidates, rrfHigh maps to 1.0 and rrfLow to 0.0.
        // The fused score difference (0.3 * 1.0 = 0.30) easily overcomes the tiny cross-encoder gap,
        // proving the fused signal is fully impactful.
        assertEquals("RRF high candidate should rank #1 because fused signal is meaningful", rrfHigh.id, outcome.results[0].id)
        assertTrue(outcome.results[0].score > outcome.results[1].score)
    }

    @Test
    fun `fused signal changes order when cross-encoder logits differ by 1_0 and RRF gap is large`() = runBlocking {
        val query = "important document"

        // Candidate A has high RRF rank (top BM25 + top Dense) -> e.g. score = 0.033f
        // Candidate B has low RRF rank (e.g. rank 30 in BM25, no Dense) -> e.g. score = 0.011f
        val candidateA = sampleCandidate(1, 0.033f, "doc_high_rrf")
        val candidateB = sampleCandidate(2, 0.011f, "doc_low_rrf")

        // Cross-encoder logits differ by 1.0:
        // Candidate B has a 1.0 higher logit than Candidate A (1.0 vs 0.0)
        coEvery { crossEncoder.score(query, candidateA.snippet, TokenizerMode.FIXED) } returns 0.0f
        coEvery { crossEncoder.score(query, candidateB.snippet, TokenizerMode.FIXED) } returns 1.0f

        val outcome = reranker.rerankDetailed(
            query = query,
            candidates = listOf(candidateA, candidateB)
        )

        // After min-max normalizing candidate.score:
        // Candidate A normFused = 1.0
        // Candidate B normFused = 0.0
        //
        // Cross scores:
        // sigmoid(0.0) = 0.5000 -> 0.7 * 0.5000 = 0.3500
        // sigmoid(1.0) ≈ 0.7311 -> 0.7 * 0.7311 = 0.5117
        //
        // Hybrid scores:
        // Candidate A = 0.3500 + 0.3 * 1.0 = 0.6500
        // Candidate B = 0.5117 + 0.3 * 0.0 = 0.5117
        //
        // Fused signal overcomes the 1.0 cross-encoder logit gap and changes the order!
        assertEquals("Candidate A (top RRF) should outrank Candidate B despite 1.0 logit deficit", candidateA.id, outcome.results[0].id)
        assertEquals("Candidate B should be ranked second", candidateB.id, outcome.results[1].id)
        assertTrue(outcome.results[0].score > outcome.results[1].score)
    }

    @Test
    fun `custom crossWeight and initialWeight are respected during reranking`() = runBlocking {
        val query = "weights test"
        val candidate = sampleCandidate(1, 0.4f, "doc_1")

        coEvery { crossEncoder.score(query, candidate.snippet, TokenizerMode.FIXED) } returns 0.0f // sigmoid(0.0) = 0.5

        val outcome = reranker.rerankDetailed(
            query = query,
            candidates = listOf(candidate),
            crossWeight = 0.5f,
            initialWeight = 0.5f
        )

        // For a single candidate, range < EPSILON -> neutral normFused = 0.5
        // 0.5 * sigmoid(0) + 0.5 * 0.5 = 0.5 * 0.5 + 0.25 = 0.50
        assertEquals(0.50f, outcome.results[0].score, 0.001f)
    }
}
