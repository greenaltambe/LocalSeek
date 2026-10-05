package com.augt.localseek.eval

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.augt.localseek.core.config.Bm25MergeMode
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.DenseQueryMode
import com.augt.localseek.ml.TokenizerMode
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.data.BenchmarkRunEntity
import com.augt.localseek.logging.BenchmarkLogger
import com.augt.localseek.retrieval.CrossEncoderReranker
import com.augt.localseek.retrieval.FusionMode
import com.augt.localseek.search.RetrieverKind
import com.augt.localseek.search.RetrieverOutcome
import com.augt.localseek.search.SearchEngine
import com.augt.localseek.search.SearchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Encapsulates an excluded benchmark run observation (e.g. timeout or skipped retriever).
 */
data class BenchmarkExclusion(
    val query: String,
    val queryId: String,
    val backend: String,
    val reason: String,
    val isTimeout: Boolean
)

/**
 * Executes evaluation suites by driving [SearchEngine] across a sequence of [RetrievalConfig]s.
 *
 * Purity Invariant:
 * BenchmarkRunner contains ZERO retrieval algorithms, ZERO rank fusion logic, and ZERO score calculation.
 * It strictly acts as a harness providing explicit [RetrievalConfig]s to [SearchEngine.search] and
 * recording [SearchOutcome] into [BenchmarkLogger].
 */
class BenchmarkRunner(
    private val context: Context,
    private val searchEngine: SearchEngine,
    private val database: AppDatabase,
    private val crossEncoderReranker: CrossEncoderReranker? = null
) {

    private val _lastExclusions = mutableListOf<BenchmarkExclusion>()
    val lastExclusions: List<BenchmarkExclusion> get() = _lastExclusions.toList()

    fun clearExclusions() {
        _lastExclusions.clear()
    }

    private fun getReranker(): CrossEncoderReranker? {
        return crossEncoderReranker ?: try {
            searchEngine.crossEncoderReranker
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "BenchmarkRunner"

        /**
         * Authoritative canonical benchmark matrix (E1-E8) for publication.
         * All arms enforce enableImage = false to ensure strict comparability against
         * the canonical text-only qrels pool.
         */
        val CANONICAL_BENCHMARK_CONFIGS: List<RetrievalConfig> = listOf(
            // E1: BM25 Baseline
            RetrievalConfig(
                enableBm25 = true,
                enableDense = false,
                enableImage = false,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E1_bm25"
            ),

            // E2: Dense EXACT Baseline
            RetrievalConfig(
                enableBm25 = false,
                enableDense = true,
                denseIndexType = DenseIndexType.EXACT,
                enableImage = false,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E2_dense_exact"
            ),

            // E3: Dense LSH Baseline
            RetrievalConfig(
                enableBm25 = false,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E3_dense_lsh"
            ),

            // E4: Hybrid Linear (Global Normalization)
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.GLOBAL_NORMALIZATION,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E4_hybrid_linear"
            ),

            // E5: Hybrid RRF
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.RRF,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E5_hybrid_rrf"
            ),

            // E6: Fusion Variants (Per-Type and Threshold)
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.PER_TYPE_NORMALIZATION,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E6_fusion_per_type"
            ),
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.PER_TYPE_WITH_THRESHOLD,
                enableRerank = false,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E6_fusion_threshold"
            ),

            // E7: Full Neural Reranking Ceiling (Linear & RRF with rerankTopK = 100)
            // Evaluates theoretical reranking quality across top-100 candidates.
            // maxRerankTimeMs is set to 120,000 ms to guarantee full completion without speed exclusions.
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.GLOBAL_NORMALIZATION,
                enableRerank = true,
                rerankTopK = 100,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E7_linear_reranked",
                maxRerankTimeMs = 120000L
            ),
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.RRF,
                enableRerank = true,
                rerankTopK = 100,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E7_rrf_reranked",
                maxRerankTimeMs = 120000L
            ),

            // E7: Production-Feasible Neural Reranking (Linear & RRF with rerankTopK = 20)
            // Reranking top-20 candidates provides a realistic on-device tradeoff between
            // ranking quality and the 500 ms latency budget, whereas rerankTopK = 100 serves
            // as the theoretical quality ceiling.
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.GLOBAL_NORMALIZATION,
                enableRerank = true,
                rerankTopK = 20,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E7_linear_rerank20",
                maxRerankTimeMs = 120000L
            ),
            RetrievalConfig(
                enableBm25 = true,
                enableDense = true,
                denseIndexType = DenseIndexType.LSH,
                adaptiveLsh = false,
                enableImage = false,
                fusionMode = FusionMode.RRF,
                enableRerank = true,
                rerankTopK = 20,
                enableDiversification = false,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "E7_rrf_rerank20",
                maxRerankTimeMs = 120000L
            ),

            // E8: Canonical Legacy reference (strictly text-only: enableImage = false)
            // maxRerankTimeMs is set to 120,000 ms to guarantee full completion without speed exclusions.
            RetrievalConfig.LEGACY.copy(
                presetName = "E8_legacy",
                enableImage = false,
                denseSkipEnabled = false,
                maxRerankTimeMs = 120000L
            )
        )

        private fun canonicalArm(name: String): RetrievalConfig =
            CANONICAL_BENCHMARK_CONFIGS.first { it.presetName == name }

        /**
         * Phase 2 arms, appended after E1-E8 (the earlier arms and their configHash values are untouched).
         * E3b is an exploratory control (Set A only); E9-E12 are the Phase 2 candidates.
         */
        val PHASE2_BENCHMARK_CONFIGS: List<RetrievalConfig> = listOf(
            // E1b: E1 with rank-based (RRF) merge of the chunk / app / contact FTS5 rankings instead of one min-max over raw scores
            canonicalArm("E1_bm25").copy(
                bm25MergeMode = Bm25MergeMode.RRF_ACROSS_TABLES,
                presetName = "E1b_bm25_rrf_tables"
            ),
            // E3b: E3 with the LSH candidate cap removed at query time (AC1: the 100-candidate cap is the cause of the recall loss)
            canonicalArm("E3_dense_lsh").copy(
                lshCandidateCap = -1,
                presetName = "E3b_dense_lsh_tuned"
            ),
            // E9: E5 with exact in-memory dense search
            canonicalArm("E5_hybrid_rrf").copy(
                denseIndexType = DenseIndexType.EXACT_MEMORY,
                presetName = "E9_hybrid_rrf_exact"
            ),
            // E10: E7_rrf_rerank20 with exact in-memory dense search
            canonicalArm("E7_rrf_rerank20").copy(
                denseIndexType = DenseIndexType.EXACT_MEMORY,
                presetName = "E10_hybrid_rrf_exact_rerank20"
            ),
            // E11: E9 with the raw user query as the dense query (no stop-word stripping, no synonyms)
            canonicalArm("E5_hybrid_rrf").copy(
                denseIndexType = DenseIndexType.EXACT_MEMORY,
                denseQueryMode = DenseQueryMode.RAW,
                presetName = "E11_hybrid_rrf_exact_rawdense"
            ),
            // E12: the configuration the app ships (see shippedReplica)
            shippedReplica()
        )

        /**
         * E12, "the configuration the app shipped before task AM" (a registered arm; its name stays). Written as an explicit
         * literal so it no longer depends on RetrievalConfig.DEFAULT; ArmHashFixtureTest proves it equals the old config:
         * dense skip ON, diversification (MMR) ON, adaptive LSH ON, rerank OFF, query expansion ON, returnTopK 20,
         * GLOBAL_NORMALIZATION, benchmarkMode OFF (so denseSkipEnabled true and maxRerankTimeMs 500). Images are OFF for
         * comparability with the text-only qrels.
         */
        fun shippedReplica(): RetrievalConfig = RetrievalConfig(
            enableBm25 = true,
            enableDense = true,
            enableImage = false,
            denseIndexType = DenseIndexType.LSH,
            fusionMode = FusionMode.GLOBAL_NORMALIZATION,
            enableRerank = false,
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
            benchmarkMode = false,
            denseSkipEnabled = true,
            presetName = "E12_shipped_replica",
            adaptiveLsh = true,
            typePriorEnabled = false,
            perSpaceNorm = true,
            rerankBeforeDiversify = true,
            symmetricFallback = true,
            includeSynonymsInBm25 = false,
            tokenizerMode = TokenizerMode.FIXED,
            maxRerankTimeMs = 500L,
            imagePromptTemplate = null,
            bm25MergeMode = Bm25MergeMode.MINMAX_ALL,
            denseQueryMode = DenseQueryMode.EXPANDED,
            lshCandidateCap = 0
        )

        /** Default arm list of the canonical benchmark test: E1-E8 unchanged, then the Phase 2 arms. */
        val ALL_BENCHMARK_CONFIGS: List<RetrievalConfig> = CANONICAL_BENCHMARK_CONFIGS + PHASE2_BENCHMARK_CONFIGS

        /**
         * `-e arms a,b,c`: keeps the listed arms in the order of [all]; null or blank keeps every arm.
         * An unknown arm name is an error (a typo must not silently shrink a run).
         */
        fun selectArms(all: List<RetrievalConfig>, armsCsv: String?): List<RetrievalConfig> {
            if (armsCsv.isNullOrBlank()) return all
            val wanted = armsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val known = all.mapNotNull { it.presetName }.toSet()
            val unknown = wanted - known
            require(unknown.isEmpty()) { "Unknown arm(s) in -e arms: ${unknown.sorted()}; known: ${known.sorted()}" }
            return all.filter { it.presetName in wanted }
        }

        /** `-e outPrefix p`: prepended to every output file name; the default (null/blank) keeps today's names. */
        fun prefixedName(outPrefix: String?, fileName: String): String {
            val p = outPrefix.orEmpty()
            require(p.all { it.isLetterOrDigit() || it == '_' || it == '-' }) { "outPrefix may contain only letters, digits, '_' and '-'" }
            return p + fileName
        }

        /** Additive export provenance: the LSH structure and index generation that served LSH arms. */
        fun lshProvenance(config: com.augt.localseek.search.vector.LshConfig, vectorCount: Int, generation: Long): org.json.JSONObject =
            org.json.JSONObject()
                .put("numTables", config.numTables)
                .put("numHashBits", config.numHashBits)
                .put("projectionDim", config.projectionDim)
                .put("searchCandidates", config.searchCandidates)
                .put("memoryMode", config.memoryMode.name)
                .put("probeRadius", config.probeRadius)
                .put("vectorCount", vectorCount)
                .put("generation", generation)

        /**
         * Image benchmark matrix (I1-I2) for evaluating neural vs non-neural image retrieval.
         */
        val IMAGE_BENCHMARK_CONFIGS: List<RetrievalConfig> = listOf(
            // I1: Neural text-to-image (CLIP only)
            RetrievalConfig(
                enableBm25 = false,
                enableDense = false,
                enableImage = true,
                imageTopK = 20,
                imageThreshold = 0.0f,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "I1_clip_text2image"
            ),

            // I2: Non-neural lexical baseline over image filename/metadata
            RetrievalConfig(
                enableBm25 = false,
                enableDense = false,
                enableImage = true,
                imageTopK = 20,
                imageThreshold = 0.0f,
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "I2_filename_bm25_baseline"
            ),

            // I3: Neural text-to-image with prompt engineering template
            RetrievalConfig(
                enableBm25 = false,
                enableDense = false,
                enableImage = true,
                imageTopK = 20,
                imageThreshold = 0.0f,
                imagePromptTemplate = "a photo of {query}",
                denseSkipEnabled = false,
                benchmarkMode = true,
                presetName = "I3_clip_prompt_template"
            )
        )

        /**
         * Deprecated exploratory 6-arm benchmark matrix from Phase 24.
         */
        @Deprecated(
            message = "Use CANONICAL_BENCHMARK_CONFIGS for publication benchmarks (E1-E8). STANDARD_BENCHMARK_CONFIGS is retained only for historical reproduction of the Phase 24 6-arm suite.",
            replaceWith = ReplaceWith("CANONICAL_BENCHMARK_CONFIGS")
        )
        val STANDARD_BENCHMARK_CONFIGS = listOf(
            RetrievalConfig.BM25,
            RetrievalConfig.DENSE_LSH,
            RetrievalConfig.DENSE_BRUTE_FORCE,
            RetrievalConfig.HYBRID_GLOBAL,
            RetrievalConfig.HYBRID_PER_TYPE,
            RetrievalConfig.HYBRID_THRESHOLD
        )
    }

    /**
     * Executes the standard benchmark suite for a given [query] under a [runSessionId].
     */
    suspend fun runBenchmarkSuite(
        query: String,
        runSessionId: String,
        configs: List<RetrievalConfig> = CANONICAL_BENCHMARK_CONFIGS,
        repetitionIndex: Int = 0
    ): List<BenchmarkRunEntity> = withContext(Dispatchers.Default) {
        val records = mutableListOf<BenchmarkRunEntity>()

        val corpusSizeChunks = try { database.chunkDao().countAllChunks() } catch (_: Exception) { 0 }
        val corpusSizeApps = try { database.appDao().getCount() } catch (_: Exception) { 0 }
        val corpusSizeContacts = try { database.contactDao().getCount() } catch (_: Exception) { 0 }
        val corpusSizeImages = try { database.imageDao().getCount() } catch (_: Exception) { 0 }

        val queryId = query.trim().lowercase().hashCode().toString()
        val deviceModel = try { Build.MODEL ?: "unknown" } catch (_: Throwable) { "unknown" }
        val androidVersion = try { Build.VERSION.RELEASE ?: "unknown" } catch (_: Throwable) { "unknown" }

        for (config in configs) {
            // Experimental Integrity: Invalidate cross-encoder score cache before EACH configuration run
            getReranker()?.clearCache()

            val batBefore = getBatteryPct()

            // Strictly route through SearchEngine.search
            val outcome: SearchOutcome = searchEngine.search(query, config)

            val batAfter = getBatteryPct()

            // Enforcement of NEW-26: A skipped retriever must never log a benchmark row for that backend
            if (config.enableDense && !config.enableBm25) {
                val denseOutcome = outcome.candidatesByRetriever[RetrieverKind.DENSE]
                if (denseOutcome is RetrieverOutcome.Skipped) {
                    val msg = "dense retriever was skipped (${denseOutcome.reason})"
                    Log.w(TAG, "Skipping benchmark record for ${config.presetName}: $msg")
                    _lastExclusions.add(BenchmarkExclusion(query, queryId, config.presetName ?: "unknown", msg, false))
                    continue
                }
            } else if (config.enableBm25 && !config.enableDense) {
                val bm25Outcome = outcome.candidatesByRetriever[RetrieverKind.BM25]
                if (bm25Outcome is RetrieverOutcome.Skipped) {
                    val msg = "BM25 retriever was skipped (${bm25Outcome.reason})"
                    Log.w(TAG, "Skipping benchmark record for ${config.presetName}: $msg")
                    _lastExclusions.add(BenchmarkExclusion(query, queryId, config.presetName ?: "unknown", msg, false))
                    continue
                }
            } else if (config.enableRerank && outcome.rerankTimedOut) {
                val msg = "cross-encoder reranking timed out after ${config.maxRerankTimeMs}ms"
                Log.w(TAG, "TIMEOUT EXCLUSION: backend=${config.presetName}, query='$query' (id=$queryId) exceeded timeout ${config.maxRerankTimeMs}ms")
                _lastExclusions.add(BenchmarkExclusion(query, queryId, config.presetName ?: "unknown", msg, true))
                continue
            }

            // Assert returned entity types are a subset of what config enables
            val allowedEntityTypes = mutableSetOf<com.augt.localseek.model.EntityType>()
            if (config.enableBm25 || config.enableDense) {
                allowedEntityTypes.add(com.augt.localseek.model.EntityType.FILE)
                allowedEntityTypes.add(com.augt.localseek.model.EntityType.APP)
                allowedEntityTypes.add(com.augt.localseek.model.EntityType.CONTACT)
            }
            if (config.enableImage) {
                allowedEntityTypes.add(com.augt.localseek.model.EntityType.IMAGE)
            }

            val returnedEntityTypes = outcome.results.map { it.entityType }.toSet()
            val isEntityTypesValid = returnedEntityTypes.all { it in allowedEntityTypes }
            if (!isEntityTypesValid) {
                Log.e(TAG, "Entity type constraint violation for ${config.presetName}: returned $returnedEntityTypes but config enables $allowedEntityTypes")
            }
            try {
                assert(isEntityTypesValid) {
                    "Entity type constraint violation for ${config.presetName}: returned $returnedEntityTypes but config enables $allowedEntityTypes"
                }
            } catch (_: AssertionError) {
                // Logged and marked invalid on record
            }

            // Log whether rerank hit 500 ms budget
            val rerankLatency = outcome.rerankLatencyMs ?: 0L
            val rerankTimedOut = outcome.rerankTimedOut || (config.enableRerank && rerankLatency > 500L)
            if (config.enableRerank) {
                Log.i(TAG, "Rerank budget check for backend=${config.presetName}, query='$query': latency=${rerankLatency}ms, hit500msBudget=$rerankTimedOut")
            }

            val backendName = config.presetName ?: "custom_${config.configHash().take(8)}"
            val topResults = outcome.results.take(20)

            val record = BenchmarkRunEntity(
                runSessionId = runSessionId,
                queryId = queryId,
                queryText = query,
                timestamp = System.currentTimeMillis(),
                deviceModel = deviceModel,
                androidVersion = androidVersion,
                backend = backendName,
                corpusSizeChunks = corpusSizeChunks,
                corpusSizeApps = corpusSizeApps,
                corpusSizeContacts = corpusSizeContacts,
                latencyBm25Ms = outcome.bm25LatencyMs,
                latencyDenseMs = outcome.denseLatencyMs,
                latencyFusionMs = outcome.fusionLatencyMs,
                latencyRerankMs = outcome.rerankLatencyMs,
                latencyTotalMs = outcome.totalLatencyMs,
                memoryMbPeak = outcome.memoryPeakMb,
                batteryPctBefore = batBefore,
                batteryPctAfter = batAfter,
                resultIdsJson = JSONArray(topResults.map { "${it.entityType}:${if (it.stableKey.isNotBlank()) it.stableKey else it.id}" }).toString(),
                resultScoresJson = JSONArray(topResults.map { it.bestScore.toDouble() }).toString(),
                resultEntityTypesJson = JSONArray(topResults.map { it.entityType.name }).toString(),
                resultTitlesJson = JSONArray(topResults.map { it.title }).toString(),
                resultSnippetsJson = JSONArray(topResults.map { it.snippets.firstOrNull().orEmpty() }).toString(),
                configHash = config.configHash(),
                configJson = config.toCanonicalJson(),
                indexGeneration = 0L,
                corpusSizeImages = corpusSizeImages,
                batteryBand = getBatteryBand(batBefore),
                thermalStatus = getThermalStatus()
            ).apply {
                this.repetitionIndex = repetitionIndex
                this.isValid = isEntityTypesValid
                this.rerankTimedOut = rerankTimedOut
            }

            BenchmarkLogger.logRun(context, record)
            records.add(record)
        }

        records
    }

    private fun getBatteryPct(): Int? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            null
        }
    }

    private fun getBatteryBand(pct: Int?): String {
        return when {
            pct == null -> "UNKNOWN"
            pct < 20 -> "CRITICAL"
            pct < 50 -> "LOW"
            else -> "NORMAL"
        }
    }

    private fun getThermalStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
            } catch (_: Exception) {
                "UNKNOWN"
            }
        } else {
            "NOT_SUPPORTED"
        }
    }
}
