package com.augt.localseek.core.config

import com.augt.localseek.retrieval.FusionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalConfigTest {

    @Test
    fun `canonical JSON has strictly sorted keys and is deterministic`() {
        val config1 = RetrievalConfig(
            presetName = "test_preset",
            enableBm25 = true,
            enableDense = false,
            bm25TopK = 50,
            denseTopK = 25,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION
        )
        val config2 = RetrievalConfig(
            presetName = "test_preset",
            enableBm25 = true,
            enableDense = false,
            bm25TopK = 50,
            denseTopK = 25,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION
        )

        val json1 = config1.toCanonicalJson()
        val json2 = config2.toCanonicalJson()

        assertEquals(json1, json2)

        // Verify keys are strictly alphabetical in the serialized string
        val keys = mutableListOf<String>()
        val regex = "\"([a-zA-Z0-9]+)\":".toRegex()
        regex.findAll(json1).forEach { match ->
            keys.add(match.groupValues[1])
        }

        val sortedKeys = keys.sorted()
        assertEquals("Keys must be strictly alphabetical", sortedKeys, keys)
    }

    @Test
    fun `configHash is stable SHA-256 hex string and distinguishes differing configs`() {
        val configA = RetrievalConfig.DEFAULT
        val configB = RetrievalConfig.DEFAULT.copy(enableDense = false)
        val configC = RetrievalConfig.DEFAULT.copy()

        assertEquals("Equal configs must have identical hash", configA.configHash(), configC.configHash())
        assertEquals(64, configA.configHash().length) // 256 bits = 64 hex chars
        assertNotEquals("Different configs must have different hash", configA.configHash(), configB.configHash())
    }

    @Test
    fun `roundtrip deserialization preserves all config properties`() {
        val original = RetrievalConfig(
            presetName = "custom_test",
            enableBm25 = false,
            enableDense = true,
            enableImage = false,
            bm25TopK = 80,
            denseTopK = 40,
            imageTopK = 15,
            returnTopK = 10,
            rerankTopK = 30,
            denseIndexType = DenseIndexType.EXACT,
            denseSkipThreshold = 0.92f,
            imageThreshold = 0.25f,
            fusionMode = FusionMode.PER_TYPE_NORMALIZATION,
            enableDiversification = false,
            enableRerank = false,
            enableQueryExpansion = false,
            benchmarkMode = true
        )

        val json = original.toCanonicalJson()
        val restored = RetrievalConfig.fromCanonicalJson(json)

        assertEquals(original, restored)
        assertEquals(original.configHash(), restored.configHash())
    }

    @Test
    fun `standard presets conform to expected experimental arm flags`() {
        // BM25 arm
        assertTrue(RetrievalConfig.BM25.enableBm25)
        assertFalse(RetrievalConfig.BM25.enableDense)
        assertFalse(RetrievalConfig.BM25.enableImage)
        assertEquals("bm25", RetrievalConfig.BM25.presetName)

        // Dense LSH arm
        assertFalse(RetrievalConfig.DENSE_LSH.enableBm25)
        assertTrue(RetrievalConfig.DENSE_LSH.enableDense)
        assertFalse(RetrievalConfig.DENSE_LSH.enableImage)
        assertEquals(DenseIndexType.LSH, RetrievalConfig.DENSE_LSH.denseIndexType)
        assertEquals("dense_lsh", RetrievalConfig.DENSE_LSH.presetName)

        // Dense Brute Force arm
        assertFalse(RetrievalConfig.DENSE_BRUTE_FORCE.enableBm25)
        assertTrue(RetrievalConfig.DENSE_BRUTE_FORCE.enableDense)
        assertEquals(DenseIndexType.EXACT, RetrievalConfig.DENSE_BRUTE_FORCE.denseIndexType)
        assertEquals("dense_bruteforce", RetrievalConfig.DENSE_BRUTE_FORCE.presetName)

        // Hybrid arms
        assertTrue(RetrievalConfig.HYBRID_GLOBAL.enableBm25)
        assertTrue(RetrievalConfig.HYBRID_GLOBAL.enableDense)
        assertEquals(FusionMode.GLOBAL_NORMALIZATION, RetrievalConfig.HYBRID_GLOBAL.fusionMode)

        assertTrue(RetrievalConfig.HYBRID_PER_TYPE.enableBm25)
        assertTrue(RetrievalConfig.HYBRID_PER_TYPE.enableDense)
        assertEquals(FusionMode.PER_TYPE_NORMALIZATION, RetrievalConfig.HYBRID_PER_TYPE.fusionMode)

        assertTrue(RetrievalConfig.HYBRID_THRESHOLD.enableBm25)
        assertTrue(RetrievalConfig.HYBRID_THRESHOLD.enableDense)
        assertEquals(FusionMode.PER_TYPE_WITH_THRESHOLD, RetrievalConfig.HYBRID_THRESHOLD.fusionMode)

        // Legacy preset reproduces pre-refactor state
        assertEquals("legacy", RetrievalConfig.LEGACY.presetName)
        assertEquals(FusionMode.GLOBAL_NORMALIZATION, RetrievalConfig.LEGACY.fusionMode)
        assertTrue(RetrievalConfig.LEGACY.enableDiversification)
        assertTrue(RetrievalConfig.LEGACY.adaptiveLsh)
        assertTrue(RetrievalConfig.LEGACY.typePriorEnabled)
        assertFalse(RetrievalConfig.LEGACY.perSpaceNorm)
        assertFalse(RetrievalConfig.LEGACY.rerankBeforeDiversify)
        assertFalse(RetrievalConfig.LEGACY.symmetricFallback)
        assertTrue(RetrievalConfig.LEGACY.includeSynonymsInBm25)
        assertEquals(com.augt.localseek.ml.TokenizerMode.LEGACY, RetrievalConfig.LEGACY.tokenizerMode)

        // Clean preset enforces all confound fixes
        assertEquals("clean", RetrievalConfig.CLEAN.presetName)
        assertFalse(RetrievalConfig.CLEAN.enableDiversification)
        assertFalse(RetrievalConfig.CLEAN.adaptiveLsh)
        assertFalse(RetrievalConfig.CLEAN.typePriorEnabled)
        assertTrue(RetrievalConfig.CLEAN.perSpaceNorm)
        assertTrue(RetrievalConfig.CLEAN.rerankBeforeDiversify)
        assertTrue(RetrievalConfig.CLEAN.symmetricFallback)
        assertFalse(RetrievalConfig.CLEAN.includeSynonymsInBm25)
        assertEquals(com.augt.localseek.ml.TokenizerMode.FIXED, RetrievalConfig.CLEAN.tokenizerMode)

        // RRF preset
        assertEquals("rrf", RetrievalConfig.RRF.presetName)
        assertEquals(FusionMode.RRF, RetrievalConfig.RRF.fusionMode)

        // Verify maxRerankTimeMs behavior: interactive default retains 500ms, benchmarks use 5000ms
        assertEquals(500L, RetrievalConfig.DEFAULT.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.CLEAN.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.LEGACY.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.HYBRID_GLOBAL.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.HYBRID_PER_TYPE.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.HYBRID_THRESHOLD.maxRerankTimeMs)
        assertEquals(5000L, RetrievalConfig.RRF.maxRerankTimeMs)
    }

    @Test
    fun `canonical JSON contains exactly 29 sorted fields`() {
        val json = RetrievalConfig.DEFAULT.toCanonicalJson()
        val obj = JSONObject(json)
        assertEquals(29, obj.length())
    }

    @Test
    fun `imagePromptTemplate defaults to null and roundtrips correctly`() {
        val defaultConfig = RetrievalConfig.DEFAULT
        assertEquals(null, defaultConfig.imagePromptTemplate)

        val i3Config = RetrievalConfig(
            presetName = "I3_clip_prompt_template",
            enableBm25 = false,
            enableDense = false,
            enableImage = true,
            imageTopK = 20,
            imageThreshold = 0.0f,
            imagePromptTemplate = "a photo of {query}"
        )
        assertEquals("a photo of {query}", i3Config.imagePromptTemplate)

        val json = i3Config.toCanonicalJson()
        assertTrue(json.contains("\"imagePromptTemplate\":\"a photo of {query}\""))
        val restored = RetrievalConfig.fromCanonicalJson(json)
        assertEquals(i3Config, restored)
        assertEquals("a photo of {query}", restored.imagePromptTemplate)

        // Null template serialization
        val nullRestored = RetrievalConfig.fromCanonicalJson(defaultConfig.toCanonicalJson())
        assertEquals(null, nullRestored.imagePromptTemplate)
    }
}
