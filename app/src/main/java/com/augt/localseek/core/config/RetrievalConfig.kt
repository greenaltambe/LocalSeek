package com.augt.localseek.core.config

import com.augt.localseek.ml.TokenizerMode
import com.augt.localseek.retrieval.FusionMode
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Supported index selection strategies for dense vector retrieval.
 */
enum class DenseIndexType {
    LSH,
    EXACT,
    /** Exact search over an in-memory contiguous copy of the chunk embeddings (falls back to EXACT above the size guard). */
    EXACT_MEMORY,
    /**
     * Size-adaptive (the shipped setting): exact in-memory search up to 50,000 chunk vectors (identical to EXACT_MEMORY),
     * binary shortlist plus float rescoring (k' = 200) above that.
     */
    AUTO
}

/** How BM25 hits from the chunks, apps and contacts FTS5 tables are merged into one ranked list. */
enum class Bm25MergeMode {
    /** Original behaviour: one min-max over the raw FTS5 scores of all three tables. */
    MINMAX_ALL,
    /** Rank-based reciprocal-rank merge of the three per-table rankings. */
    RRF_ACROSS_TABLES
}

/** What text the dense (MiniLM) encoder sees. */
enum class DenseQueryMode {
    /** Original behaviour: stop-word-stripped query plus synonyms (when query expansion is on). */
    EXPANDED,
    /** The user's query only: trimmed, lower-cased, whitespace collapsed. */
    RAW
}

/**
 * Canonical retrieval configuration for LocalSeek.
 *
 * Exposes explicit knobs for all retrieval, index, and ranking stages.
 * Guaranteed to have:
 * - Deterministic field ordering in canonical JSON
 * - Deterministic SHA-256 fingerprint (configHash)
 * - Deterministic structural equality
 */
data class RetrievalConfig(
    val enableBm25: Boolean = true,
    val enableDense: Boolean = true,
    val enableImage: Boolean = true,
    val denseIndexType: DenseIndexType = DenseIndexType.LSH,
    val fusionMode: FusionMode = FusionMode.GLOBAL_NORMALIZATION,
    val enableRerank: Boolean = true,
    val enableQueryExpansion: Boolean = true,
    val enableDiversification: Boolean = true,
    val bm25TopK: Int = 100,
    val denseTopK: Int = 50,
    val imageTopK: Int = 20,
    val rerankTopK: Int = 100,
    val returnTopK: Int = 20,
    val denseSkipThreshold: Float = 0.85f,
    val imageThreshold: Float = 0.25f,
    val crossWeight: Float = 0.7f,
    val initialWeight: Float = 0.3f,
    val benchmarkMode: Boolean = false,
    val denseSkipEnabled: Boolean = !benchmarkMode,
    val presetName: String? = null,
    val adaptiveLsh: Boolean = false,
    val typePriorEnabled: Boolean = false,
    val perSpaceNorm: Boolean = true,
    val rerankBeforeDiversify: Boolean = true,
    val symmetricFallback: Boolean = true,
    val includeSynonymsInBm25: Boolean = false,
    val tokenizerMode: TokenizerMode = TokenizerMode.FIXED,
    val maxRerankTimeMs: Long = if (benchmarkMode) 5000L else 500L,
    val imagePromptTemplate: String? = null,
    // Phase 2 options. Defaults reproduce the pre-Phase-2 behaviour and are omitted from the canonical JSON when default,
    // so the configHash of every earlier arm is unchanged.
    val bm25MergeMode: Bm25MergeMode = Bm25MergeMode.MINMAX_ALL,
    val denseQueryMode: DenseQueryMode = DenseQueryMode.EXPANDED,
    /** LSH query-time candidate cap override; 0 keeps the index configuration, a negative value removes the cap. */
    val lshCandidateCap: Int = 0
) {

    /**
     * Serializes configuration to a deterministic JSON string with keys strictly sorted alphabetically.
     * Guaranteed invariant across JVM instances, platforms, and processes.
     */
    fun toCanonicalJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"adaptiveLsh\":").append(adaptiveLsh).append(",")
        sb.append("\"benchmarkMode\":").append(benchmarkMode).append(",")
        if (bm25MergeMode != Bm25MergeMode.MINMAX_ALL) sb.append("\"bm25MergeMode\":\"").append(bm25MergeMode.name).append("\",")
        sb.append("\"bm25TopK\":").append(bm25TopK).append(",")
        sb.append("\"crossWeight\":").append(crossWeight).append(",")
        sb.append("\"denseIndexType\":\"").append(denseIndexType.name).append("\",")
        if (denseQueryMode != DenseQueryMode.EXPANDED) sb.append("\"denseQueryMode\":\"").append(denseQueryMode.name).append("\",")
        sb.append("\"denseSkipEnabled\":").append(denseSkipEnabled).append(",")
        sb.append("\"denseSkipThreshold\":").append(denseSkipThreshold).append(",")
        sb.append("\"denseTopK\":").append(denseTopK).append(",")
        sb.append("\"enableBm25\":").append(enableBm25).append(",")
        sb.append("\"enableDense\":").append(enableDense).append(",")
        sb.append("\"enableDiversification\":").append(enableDiversification).append(",")
        sb.append("\"enableImage\":").append(enableImage).append(",")
        sb.append("\"enableQueryExpansion\":").append(enableQueryExpansion).append(",")
        sb.append("\"enableRerank\":").append(enableRerank).append(",")
        sb.append("\"fusionMode\":\"").append(fusionMode.name).append("\",")
        sb.append("\"imagePromptTemplate\":").append(if (imagePromptTemplate == null) "null" else "\"$imagePromptTemplate\"").append(",")
        sb.append("\"imageThreshold\":").append(imageThreshold).append(",")
        sb.append("\"imageTopK\":").append(imageTopK).append(",")
        sb.append("\"includeSynonymsInBm25\":").append(includeSynonymsInBm25).append(",")
        sb.append("\"initialWeight\":").append(initialWeight).append(",")
        if (lshCandidateCap != 0) sb.append("\"lshCandidateCap\":").append(lshCandidateCap).append(",")
        sb.append("\"maxRerankTimeMs\":").append(maxRerankTimeMs).append(",")
        sb.append("\"perSpaceNorm\":").append(perSpaceNorm).append(",")
        sb.append("\"presetName\":").append(if (presetName == null) "null" else "\"$presetName\"").append(",")
        sb.append("\"rerankBeforeDiversify\":").append(rerankBeforeDiversify).append(",")
        sb.append("\"rerankTopK\":").append(rerankTopK).append(",")
        sb.append("\"returnTopK\":").append(returnTopK).append(",")
        sb.append("\"symmetricFallback\":").append(symmetricFallback).append(",")
        sb.append("\"tokenizerMode\":\"").append(tokenizerMode.name).append("\",")
        sb.append("\"typePriorEnabled\":").append(typePriorEnabled)
        sb.append("}")
        return sb.toString()
    }

    /**
     * Stable SHA-256 hex digest of the canonical JSON representation.
     */
    fun configHash(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toCanonicalJson().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Standard production configuration.
         */
        val DEFAULT = RetrievalConfig(
            presetName = "default"
        )

        /**
         * The configuration the app ships (v1.0.0): registered arm E9 (hybrid RRF, exact in-memory dense search, no rerank,
         * no MMR, no dense skip) for text, with image search kept as before (image fields as the app used them). The one
         * difference from E9 is denseIndexType AUTO: identical to E9's exact in-memory search up to 50,000 chunk vectors, binary
         * shortlist plus float rescoring above. Other text-retrieval fields equal E9 exactly; benchmarkMode is false (it only affects caching and logging, and
         * maxRerankTimeMs only acts when rerank is on, which it is not). Constructor defaults are unchanged.
         */
        val SHIPPED = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            enableImage = true,
            denseIndexType = DenseIndexType.AUTO,
            fusionMode = FusionMode.RRF,
            enableRerank = false,
            enableQueryExpansion = true,
            enableDiversification = false,
            bm25TopK = 100,
            denseTopK = 50,
            imageTopK = 20,
            rerankTopK = 100,
            returnTopK = 20,
            denseSkipThreshold = 0.85f,
            imageThreshold = 0.25f,
            crossWeight = 0.7f,
            initialWeight = 0.3f,
            benchmarkMode = false,
            denseSkipEnabled = false,
            presetName = "shipped_e9",
            adaptiveLsh = false,
            typePriorEnabled = false,
            perSpaceNorm = true,
            rerankBeforeDiversify = true,
            symmetricFallback = true,
            includeSynonymsInBm25 = false,
            tokenizerMode = TokenizerMode.FIXED,
            maxRerankTimeMs = 500L
        )

        /**
         * Clean, unconfounded configuration for future evaluations.
         * Default exact vector index, RRF/calibrated normalization, MMR off, fixed BertTokenizer.
         */
        val CLEAN = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            enableImage = true,
            denseIndexType = DenseIndexType.EXACT,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION,
            enableRerank = true,
            enableQueryExpansion = true,
            enableDiversification = false,
            bm25TopK = 100,
            denseTopK = 50,
            imageTopK = 20,
            rerankTopK = 100,
            returnTopK = 20,
            denseSkipThreshold = 0.85f,
            imageThreshold = 0.25f,
            crossWeight = 0.7f,
            initialWeight = 0.3f,
            benchmarkMode = true,
            presetName = "clean",
            adaptiveLsh = false,
            typePriorEnabled = false,
            perSpaceNorm = true,
            rerankBeforeDiversify = true,
            symmetricFallback = true,
            includeSynonymsInBm25 = false,
            tokenizerMode = TokenizerMode.FIXED,
            maxRerankTimeMs = 5000L
        )

        /**
         * Preserves pre-Phase-5 legacy pipeline behavior for Phase 4 reproduction gate.
         * Intentionally retains:
         * - LSH index query adaptation
         * - Global score normalization across MiniLM and CLIP
         * - MMR diversification applied before reranking
         * - Legacy tokenization and synonym routing
         */
        val LEGACY = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            enableImage = true,
            denseIndexType = DenseIndexType.LSH,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION,
            enableRerank = true,
            enableQueryExpansion = true,
            enableDiversification = true,
            bm25TopK = 100,
            denseTopK = 50,
            imageTopK = 20,
            rerankTopK = 100,
            returnTopK = 20,
            denseSkipThreshold = 0.85f,
            imageThreshold = 0.25f,
            crossWeight = 0.7f,
            initialWeight = 0.3f,
            benchmarkMode = true,
            presetName = "legacy",
            adaptiveLsh = true,
            typePriorEnabled = true,
            perSpaceNorm = false,
            rerankBeforeDiversify = false,
            symmetricFallback = false,
            includeSynonymsInBm25 = true,
            tokenizerMode = TokenizerMode.LEGACY,
            maxRerankTimeMs = 5000L
        )

        val BM25 = RetrievalConfig(
            enableBm25 = true,
            enableDense = false,
            enableImage = false,
            enableRerank = false,
            enableDiversification = false,
            benchmarkMode = true,
            presetName = "bm25"
        )

        val DENSE_LSH = RetrievalConfig(
            enableBm25 = false,
            enableDense = true,
            denseIndexType = DenseIndexType.LSH,
            enableImage = false,
            enableRerank = false,
            enableDiversification = false,
            benchmarkMode = true,
            presetName = "dense_lsh"
        )

        val DENSE_BRUTE_FORCE = RetrievalConfig(
            enableBm25 = false,
            enableDense = true,
            denseIndexType = DenseIndexType.EXACT,
            enableImage = false,
            enableRerank = false,
            enableDiversification = false,
            benchmarkMode = true,
            presetName = "dense_bruteforce"
        )

        val HYBRID_GLOBAL = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            denseIndexType = DenseIndexType.LSH,
            enableImage = true,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION,
            enableRerank = true,
            enableDiversification = true,
            benchmarkMode = true,
            presetName = "hybrid_global",
            maxRerankTimeMs = 5000L
        )

        val HYBRID_PER_TYPE = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            denseIndexType = DenseIndexType.LSH,
            enableImage = true,
            fusionMode = FusionMode.PER_TYPE_NORMALIZATION,
            enableRerank = true,
            enableDiversification = true,
            benchmarkMode = true,
            presetName = "hybrid_per_type",
            maxRerankTimeMs = 5000L
        )

        val HYBRID_THRESHOLD = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            denseIndexType = DenseIndexType.LSH,
            enableImage = true,
            fusionMode = FusionMode.PER_TYPE_WITH_THRESHOLD,
            enableRerank = true,
            enableDiversification = true,
            benchmarkMode = true,
            presetName = "hybrid_threshold",
            maxRerankTimeMs = 5000L
        )

        val RRF = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            denseIndexType = DenseIndexType.EXACT,
            enableImage = true,
            fusionMode = FusionMode.RRF,
            enableRerank = true,
            enableDiversification = false,
            benchmarkMode = true,
            presetName = "rrf",
            adaptiveLsh = false,
            typePriorEnabled = false,
            perSpaceNorm = true,
            rerankBeforeDiversify = true,
            symmetricFallback = true,
            includeSynonymsInBm25 = false,
            tokenizerMode = TokenizerMode.FIXED,
            maxRerankTimeMs = 5000L
        )

        fun fromCanonicalJson(jsonStr: String): RetrievalConfig {
            val json = JSONObject(jsonStr)
            return RetrievalConfig(
                adaptiveLsh = json.optBoolean("adaptiveLsh", false),
                benchmarkMode = json.optBoolean("benchmarkMode", false),
                bm25MergeMode = Bm25MergeMode.valueOf(json.optString("bm25MergeMode", Bm25MergeMode.MINMAX_ALL.name)),
                bm25TopK = json.optInt("bm25TopK", 100),
                crossWeight = json.optDouble("crossWeight", 0.7).toFloat(),
                denseIndexType = DenseIndexType.valueOf(json.optString("denseIndexType", DenseIndexType.LSH.name)),
                denseQueryMode = DenseQueryMode.valueOf(json.optString("denseQueryMode", DenseQueryMode.EXPANDED.name)),
                denseSkipEnabled = if (json.has("denseSkipEnabled")) {
                    json.optBoolean("denseSkipEnabled")
                } else {
                    !json.optBoolean("benchmarkMode", false)
                },
                denseSkipThreshold = json.optDouble("denseSkipThreshold", 0.85).toFloat(),
                denseTopK = json.optInt("denseTopK", 50),
                enableBm25 = json.optBoolean("enableBm25", true),
                enableDense = json.optBoolean("enableDense", true),
                enableDiversification = json.optBoolean("enableDiversification", true),
                enableImage = json.optBoolean("enableImage", true),
                enableQueryExpansion = json.optBoolean("enableQueryExpansion", true),
                enableRerank = json.optBoolean("enableRerank", true),
                fusionMode = FusionMode.valueOf(json.optString("fusionMode", FusionMode.GLOBAL_NORMALIZATION.name)),
                imagePromptTemplate = if (json.isNull("imagePromptTemplate") || !json.has("imagePromptTemplate")) null else json.optString("imagePromptTemplate"),
                imageThreshold = json.optDouble("imageThreshold", 0.25).toFloat(),
                imageTopK = json.optInt("imageTopK", 20),
                includeSynonymsInBm25 = json.optBoolean("includeSynonymsInBm25", false),
                initialWeight = json.optDouble("initialWeight", 0.3).toFloat(),
                lshCandidateCap = json.optInt("lshCandidateCap", 0),
                maxRerankTimeMs = if (json.has("maxRerankTimeMs")) {
                    json.optLong("maxRerankTimeMs")
                } else {
                    if (json.optBoolean("benchmarkMode", false)) 5000L else 500L
                },
                perSpaceNorm = json.optBoolean("perSpaceNorm", true),
                presetName = if (json.isNull("presetName")) null else json.optString("presetName"),
                rerankBeforeDiversify = json.optBoolean("rerankBeforeDiversify", true),
                rerankTopK = json.optInt("rerankTopK", 100),
                returnTopK = json.optInt("returnTopK", 20),
                symmetricFallback = json.optBoolean("symmetricFallback", true),
                tokenizerMode = TokenizerMode.valueOf(json.optString("tokenizerMode", TokenizerMode.FIXED.name)),
                typePriorEnabled = json.optBoolean("typePriorEnabled", false)
            )
        }
    }
}
