package com.augt.localseek.ui

import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.eval.BenchmarkRunner
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.ui.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config the app builds for a search (default settings) must be registered arm E9 for text retrieval (apart from AUTO).
 *
 * Fields compared: every RetrievalConfig field (enableBm25, enableDense, denseIndexType, fusionMode, enableRerank,
 * enableQueryExpansion, enableDiversification, bm25TopK, denseTopK, imageTopK, rerankTopK, returnTopK, denseSkipThreshold,
 * imageThreshold, crossWeight, initialWeight, denseSkipEnabled, adaptiveLsh, typePriorEnabled, perSpaceNorm,
 * rerankBeforeDiversify, symmetricFallback, includeSynonymsInBm25, tokenizerMode, imagePromptTemplate, bm25MergeMode,
 * denseQueryMode, lshCandidateCap) except the four allowed differences below.
 *
 * Allowed differences:
 *  - denseIndexType: the app uses AUTO (exact in-memory search up to 50,000 chunk vectors, identical to E9's EXACT_MEMORY there;
 *    binary shortlist plus float rescoring above), E9 uses EXACT_MEMORY (AutoVectorIndexTest proves the identity below the threshold);
 *  - enableImage: image search is kept as before (E9 is text-only, comparable with the text qrels);
 *  - presetName: a label;
 *  - benchmarkMode: SearchEngine never reads it (it only controls the query cache and benchmark logging in SearchViewModel);
 *  - maxRerankTimeMs: SearchEngine passes it only inside the `config.enableRerank && ...` branches (both the clean and the
 *    legacy pipeline), and rerank is off, so it does not act.
 */
class SearchConfigFactoryTest {

    private val e9 get() = BenchmarkRunner.ALL_BENCHMARK_CONFIGS.first { it.presetName == "E9_hybrid_rrf_exact" }

    @Test
    fun `default settings give E9 apart from the allowed fields`() {
        for (images in listOf(false, true)) {
            val built = SearchConfigFactory.build(AppSettings(), imageSearchAvailable = images, benchmarkMode = false)
            val e9Adjusted = e9.copy(
                denseIndexType = DenseIndexType.AUTO,
                enableImage = images,
                presetName = built.presetName,
                benchmarkMode = built.benchmarkMode,
                maxRerankTimeMs = built.maxRerankTimeMs
            )
            assertEquals(e9Adjusted, built)
            assertEquals("shipped_e9", built.presetName)
        }
    }

    @Test
    fun `shipped config defaults are size-adaptive exact search, rrf, no rerank, no mmr, no dense skip`() {
        val c = SearchConfigFactory.build(AppSettings(), imageSearchAvailable = false, benchmarkMode = false)
        assertEquals(DenseIndexType.AUTO, c.denseIndexType)
        assertEquals(FusionMode.RRF, c.fusionMode)
        assertFalse(c.enableRerank || c.enableDiversification || c.denseSkipEnabled || c.benchmarkMode)
        assertTrue(c.enableBm25 && c.enableDense)
    }

    @Test
    fun `shipped differs from E9 only in the dense index type, image search and the allowed labels`() {
        val s = RetrievalConfig.SHIPPED
        assertEquals(DenseIndexType.AUTO, s.denseIndexType)
        assertEquals(e9.copy(denseIndexType = DenseIndexType.AUTO, enableImage = s.enableImage, presetName = s.presetName, benchmarkMode = s.benchmarkMode, maxRerankTimeMs = s.maxRerankTimeMs), s)
        assertEquals(DenseIndexType.EXACT_MEMORY, e9.denseIndexType)
    }

    @Test
    fun `AppSettings reranking defaults to off`() {
        assertFalse(AppSettings().enableReranking)
    }

    @Test
    fun `user settings only switch parts on or off`() {
        val s = AppSettings(enableDenseRetrieval = false, enableReranking = true, enableQueryExpansion = false, maxResults = 10)
        val c = SearchConfigFactory.build(s, imageSearchAvailable = false, benchmarkMode = true)
        assertFalse(c.enableDense)
        assertTrue(c.enableRerank)
        assertFalse(c.enableQueryExpansion)
        assertEquals(10, c.returnTopK)
        assertTrue(c.benchmarkMode)
        assertEquals(RetrievalConfig.SHIPPED.denseIndexType, c.denseIndexType)
    }

    @Test
    fun `per type normalisation setting still selects that fusion mode`() {
        val c = SearchConfigFactory.build(AppSettings(enablePerTypeNormalization = true), false, false)
        assertEquals(FusionMode.PER_TYPE_NORMALIZATION, c.fusionMode)
    }

    @Test
    fun `constructor defaults are unchanged`() {
        // DEFAULT is built from the constructor defaults; its hash is the pre-AM value (also pinned for E12 by ArmHashFixtureTest)
        val d = RetrievalConfig.DEFAULT
        assertEquals(DenseIndexType.LSH, d.denseIndexType)
        assertEquals(FusionMode.GLOBAL_NORMALIZATION, d.fusionMode)
        assertTrue(d.enableRerank && d.enableDiversification && d.denseSkipEnabled)
    }
}
