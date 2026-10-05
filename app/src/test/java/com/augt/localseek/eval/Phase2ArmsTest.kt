package com.augt.localseek.eval

import com.augt.localseek.core.config.Bm25MergeMode
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.DenseQueryMode
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.ui.settings.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Phase2ArmsTest {

    /** configHash of every v1.2 arm, from the paper-v1.2 canonical export (hashes only, no private data). */
    private val v12Hashes = mapOf(
        "E1_bm25" to "999382b8b4dd0ebc15af98b8735a4b892d5e34b912726cf39f92527d57cf8593",
        "E2_dense_exact" to "e02222cf0a804aca0cfe42bfed23fc651d7ed098d721356d8a667bd2c2805a5a",
        "E3_dense_lsh" to "c4da5d6fabea1af07f99008d3333b4188bb86c797e03a64a6b571c89f27253bc",
        "E4_hybrid_linear" to "992c7280d6641a289c07f2db367b522219790480db0325a6f329e051bb0acf58",
        "E5_hybrid_rrf" to "3b1cff4a352c4b1a1c1f4a9634a97ea7d060645ea3609bef5704360af86bf1ad",
        "E6_fusion_per_type" to "0f0c6252a07f705b39c2473d5da64b7050aa4a7bf80d779dc9f7f3235c7e6c60",
        "E6_fusion_threshold" to "dbc89a0ce28eb33752f5a3b1cb8c0ab8cf447772a8f854c8c73d3b5380b36562",
        "E7_linear_rerank20" to "c5cd743daf30f68d0b91a6f1d78518171829fd4fd5c557d430f7be03f59dfd97",
        "E7_linear_reranked" to "29d534107ca2bd85943bec2ccd2e39e56ba46ed3833b8a08782442f686cb8faa",
        "E7_rrf_rerank20" to "6c07f84919a41a759263f8622c106d856ea7fc20f8cab50b95d042c0151daa4f",
        "E7_rrf_reranked" to "4d33c55d63de6fc3726401c66a29340330ac3e12e2d2e502f9ab6fa196acda3a",
        "E8_legacy" to "82f11050319c24db0e079212efb363f6cf09f39e09426fc3cdfd3e0d06882eb0"
    )

    private val newArmNames = listOf(
        "E1b_bm25_rrf_tables", "E3b_dense_lsh_tuned", "E9_hybrid_rrf_exact",
        "E10_hybrid_rrf_exact_rerank20", "E11_hybrid_rrf_exact_rawdense", "E12_shipped_replica"
    )

    @Test
    fun `configHash of every existing arm equals the paper-v1_2 export value`() {
        val canonical = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS
        assertEquals(v12Hashes.keys, canonical.map { it.presetName }.toSet())
        canonical.forEach { assertEquals(it.presetName, v12Hashes[it.presetName], it.configHash()) }
    }

    @Test
    fun `configHash of existing arms also matches the local export file when it is present`() {
        val f = File("../eval/results/canonical_publication_benchmark_export.json")
        assumeTrue("export file is git-ignored and absent on this machine", f.exists())
        val runs = JSONObject(f.readText()).getJSONArray("runs")
        val fromFile = (0 until runs.length()).associate { runs.getJSONObject(it).getString("backend") to runs.getJSONObject(it).getString("configHash") }
        BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.forEach { assertEquals(it.presetName, fromFile[it.presetName], it.configHash()) }
    }

    @Test
    fun `default arm list is the unchanged canonical list followed by the new arms`() {
        val all = BenchmarkRunner.ALL_BENCHMARK_CONFIGS.map { it.presetName }
        val canonical = BenchmarkRunner.CANONICAL_BENCHMARK_CONFIGS.map { it.presetName }
        assertEquals(canonical, all.take(canonical.size))
        assertEquals(newArmNames, all.drop(canonical.size))
        assertEquals(all.size, all.toSet().size)
        val hashes = BenchmarkRunner.ALL_BENCHMARK_CONFIGS.map { it.configHash() }
        assertEquals("every arm has a distinct configuration", hashes.size, hashes.toSet().size)
    }

    @Test
    fun `new arms differ from their parents only in the intended options`() {
        val byName = BenchmarkRunner.ALL_BENCHMARK_CONFIGS.associateBy { it.presetName }
        val e1b = byName.getValue("E1b_bm25_rrf_tables")
        assertEquals(Bm25MergeMode.RRF_ACROSS_TABLES, e1b.bm25MergeMode)
        assertEquals(byName.getValue("E1_bm25").copy(bm25MergeMode = Bm25MergeMode.RRF_ACROSS_TABLES, presetName = e1b.presetName), e1b)
        val e3b = byName.getValue("E3b_dense_lsh_tuned")
        assertEquals(byName.getValue("E3_dense_lsh").copy(lshCandidateCap = -1, presetName = e3b.presetName), e3b)
        val e9 = byName.getValue("E9_hybrid_rrf_exact")
        assertEquals(byName.getValue("E5_hybrid_rrf").copy(denseIndexType = DenseIndexType.EXACT_MEMORY, presetName = e9.presetName), e9)
        val e10 = byName.getValue("E10_hybrid_rrf_exact_rerank20")
        assertEquals(byName.getValue("E7_rrf_rerank20").copy(denseIndexType = DenseIndexType.EXACT_MEMORY, presetName = e10.presetName), e10)
        val e11 = byName.getValue("E11_hybrid_rrf_exact_rawdense")
        assertEquals(e9.copy(denseQueryMode = DenseQueryMode.RAW, presetName = e11.presetName), e11)
        listOf(e1b, e3b, e9, e10, e11).forEach { assertFalse(it.presetName, it.enableImage) }
    }

    @Test
    fun `shipped replica equals the config SearchViewModel builds from default settings`() {
        val s = AppSettings()
        // mirrors SearchViewModel.performSearch: RetrievalConfig.DEFAULT.copy(...) with default settings and the default UI state
        val app = RetrievalConfig.DEFAULT.copy(
            enableDense = s.enableDenseRetrieval,
            enableRerank = s.enableReranking,
            enableQueryExpansion = s.enableQueryExpansion,
            enableImage = false,
            returnTopK = s.maxResults,
            adaptiveLsh = true, // the default of the adaptive-LSH setting, which was removed in v1.0.0 (E12 replicates the pre-AM app)
            fusionMode = FusionMode.GLOBAL_NORMALIZATION,
            benchmarkMode = false
        )
        val replica = BenchmarkRunner.shippedReplica()
        assertEquals(app.copy(presetName = "E12_shipped_replica"), replica)
        assertTrue(replica.denseSkipEnabled && replica.enableDiversification && replica.adaptiveLsh)
        assertFalse(replica.enableRerank || replica.enableImage || replica.benchmarkMode)
        assertEquals(DenseIndexType.LSH, replica.denseIndexType)
    }

    @Test
    fun `new options are omitted from canonical json at their defaults and round-trip otherwise`() {
        val base = RetrievalConfig(presetName = "x")
        assertFalse(base.toCanonicalJson().contains("bm25MergeMode"))
        assertFalse(base.toCanonicalJson().contains("denseQueryMode"))
        assertFalse(base.toCanonicalJson().contains("lshCandidateCap"))
        val c = base.copy(bm25MergeMode = Bm25MergeMode.RRF_ACROSS_TABLES, denseQueryMode = DenseQueryMode.RAW,
            lshCandidateCap = -1, denseIndexType = DenseIndexType.EXACT_MEMORY)
        assertNotEquals(base.configHash(), c.configHash())
        assertEquals(c, RetrievalConfig.fromCanonicalJson(c.toCanonicalJson()))
        val keys = Regex("\"([a-zA-Z0-9]+)\":").findAll(c.toCanonicalJson()).map { it.groupValues[1] }.toList()
        assertEquals("keys stay alphabetical", keys.sorted(), keys)
    }

    @Test
    fun `selectArms keeps default order, rejects unknown names and defaults to all`() {
        val all = BenchmarkRunner.ALL_BENCHMARK_CONFIGS
        assertEquals(all, BenchmarkRunner.selectArms(all, null))
        assertEquals(all, BenchmarkRunner.selectArms(all, "  "))
        assertEquals(listOf("E1_bm25", "E9_hybrid_rrf_exact"),
            BenchmarkRunner.selectArms(all, "E9_hybrid_rrf_exact, E1_bm25").map { it.presetName })
        try {
            BenchmarkRunner.selectArms(all, "E1_bm25,E99")
            org.junit.Assert.fail("unknown arm must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("E99"))
        }
    }

    @Test
    fun `outPrefix is prepended to file names and defaults to nothing`() {
        assertEquals("canonical_pool.csv", BenchmarkRunner.prefixedName(null, "canonical_pool.csv"))
        assertEquals("canonical_pool.csv", BenchmarkRunner.prefixedName("", "canonical_pool.csv"))
        assertEquals("p2_canonical_pool.csv", BenchmarkRunner.prefixedName("p2_", "canonical_pool.csv"))
        try {
            BenchmarkRunner.prefixedName("../x", "a.json")
            org.junit.Assert.fail("path separators must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `lsh provenance records the structure and generation`() {
        val cfg = com.augt.localseek.search.vector.LshConfig(5, 10, 64, 70, com.augt.localseek.search.vector.LshConfig.MemoryMode.IN_MEMORY, 1)
        val j = BenchmarkRunner.lshProvenance(cfg, 13843, 3L)
        assertEquals(5, j.getInt("numTables"))
        assertEquals(10, j.getInt("numHashBits"))
        assertEquals(70, j.getInt("searchCandidates"))
        assertEquals("IN_MEMORY", j.getString("memoryMode"))
        assertEquals(13843, j.getInt("vectorCount"))
        assertEquals(3L, j.getLong("generation"))
    }

    // Source-level guards on the instrumented harness (it cannot run on the JVM).
    private fun canonicalTestBody(): String {
        val src = File("src/androidTest/java/com/augt/localseek/eval/CanonicalBenchmarkInstrumentedTest.kt").readText()
        val start = src.indexOf("fun executeCanonicalBenchmark")
        val end = src.indexOf("fun executeImageBenchmark")
        assertTrue(start > 0 && end > start)
        return src.substring(start, end).lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `canonical benchmark test never writes the image pool and prefixes every output file`() {
        val body = canonicalTestBody()
        assertFalse("canonical test must not touch the image pool", body.contains("image_pool"))
        assertTrue(body.contains("BenchmarkSafety.writePool(poolCsvFile"))
        listOf("canonical_query_metadata.json", "canonical_publication_benchmark_export.json", "canonical_pool.json", "canonical_pool.csv").forEach {
            assertTrue("$it must be written through prefixedName", body.contains("prefixedName(outPrefix, \"$it\")"))
            val quoted = "\"$it\""
            assertEquals("every use of $it goes through prefixedName", body.split("prefixedName(outPrefix, $quoted").size - 1, body.split(quoted).size - 1)
        }
    }

    @Test
    fun `pool writer refuses a header-only pool so a non-empty pool is never overwritten by one`() {
        val src = File("src/androidTest/java/com/augt/localseek/eval/BenchmarkSafety.kt").readText()
        assertTrue(src.contains("PoolGuard.check(file.name, lines)"))
        val guard = File("src/debug/java/com/augt/localseek/diagnostics/PoolGuard.kt").readText()
        assertTrue(guard.contains("lines.size > 1"))
    }

    @Test
    fun `export records the lsh config only when an lsh arm runs`() {
        val body = canonicalTestBody()
        assertTrue(body.contains("lshProvenance"))
        assertTrue(body.contains("DenseIndexType.LSH && it.enableDense"))
    }
}
