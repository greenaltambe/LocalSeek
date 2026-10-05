package com.augt.localseek.search

import com.augt.localseek.core.config.Bm25MergeMode
import com.augt.localseek.core.config.DenseIndexType
import com.augt.localseek.core.config.DenseQueryMode
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.logging.measureSuspendTime
import com.augt.localseek.model.SearchResult
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.CrossEncoderReranker
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.retrieval.FusionCandidate
import com.augt.localseek.retrieval.FusionRanker
import com.augt.localseek.retrieval.ImageRetriever
import com.augt.localseek.retrieval.ResultAggregator
import com.augt.localseek.search.query.QueryProcessor
import com.augt.localseek.search.vector.LshVectorIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Unified search orchestration engine for LocalSeek.
 *
 * Implements the Single Search Path Invariant:
 * Both production UI searches and benchmark suite executions MUST invoke [search] with an explicit [RetrievalConfig].
 */
class SearchEngine(
    private val bm25Retriever: BM25Retriever,
    private val denseRetriever: DenseRetriever?,
    private val exactDenseRetriever: DenseRetriever? = null,
    private val imageRetriever: ImageRetriever,
    val crossEncoderReranker: CrossEncoderReranker,
    private val queryProcessor: QueryProcessor,
    private val fusionRanker: FusionRanker = FusionRanker(),
    private val indexGenerationProvider: () -> Long = { 0L },
    private val imageFilenameRetriever: com.augt.localseek.retrieval.ImageFilenameRetriever? = null,
    private val exactMemoryDenseRetriever: DenseRetriever? = null,
    private val autoDenseRetriever: DenseRetriever? = null
) {

    /**
     * Executes heterogeneous search according to the specified [config].
     */
    suspend fun search(rawQuery: String, config: RetrievalConfig): SearchOutcome = withContext(Dispatchers.Default) {
        val totalStart = System.currentTimeMillis()

        if (rawQuery.isBlank()) {
            return@withContext SearchOutcome(
                query = rawQuery,
                config = config,
                results = emptyList(),
                candidatesByRetriever = mapOf(
                    RetrieverKind.BM25 to RetrieverOutcome.Skipped("Empty query"),
                    RetrieverKind.DENSE to RetrieverOutcome.Skipped("Empty query"),
                    RetrieverKind.IMAGE to RetrieverOutcome.Skipped("Empty query")
                ),
                totalLatencyMs = 0L,
                indexGeneration = indexGenerationProvider()
            )
        }

        // 1. Process query with config-governed synonym expansion routing
        val processed = queryProcessor.process(rawQuery, config.includeSynonymsInBm25)
        val query = processed.normalized.normalized.trim().lowercase()
        val bm25Query = if (config.enableQueryExpansion) processed.bm25Query else processed.normalized.normalized
        val denseQuery = if (config.enableQueryExpansion) processed.denseQuery else processed.normalized.normalized

        // 2. Parallel retrieval
        var bm25LatencyMs = 0L
        var denseLatencyMs = 0L
        val (candidatesByRetriever, bm25Results, denseResults, imageResults) = coroutineScope {
            val bm25Deferred = async {
                if (!config.enableBm25) {
                    RetrieverOutcome.Skipped("Disabled by config")
                } else {
                    try {
                        val (results, latency) = measureSuspendTime("BM25") {
                            if (config.bm25MergeMode == Bm25MergeMode.MINMAX_ALL) {
                                bm25Retriever.search(bm25Query, config.bm25TopK, config.symmetricFallback)
                            } else {
                                bm25Retriever.search(bm25Query, config.bm25TopK, config.symmetricFallback, config.bm25MergeMode)
                            }
                        }
                        bm25LatencyMs = latency
                        RetrieverOutcome.Ran(results, latency)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        RetrieverOutcome.Failed(e.message ?: "BM25 retrieval failed")
                    }
                }
            }

            val imageDeferred = async {
                if (!config.enableImage) {
                    RetrieverOutcome.Skipped("Disabled by config")
                } else {
                    try {
                        val (results, latency) = measureSuspendTime("Image") {
                            if (config.presetName == "I2_filename_bm25_baseline" && imageFilenameRetriever != null) {
                                imageFilenameRetriever.search(rawQuery, config.imageTopK)
                            } else {
                                val queryForClip = if (config.imagePromptTemplate != null) {
                                    config.imagePromptTemplate.replace("{query}", denseQuery)
                                } else {
                                    denseQuery
                                }
                                imageRetriever.search(queryForClip, config.imageTopK, config.imageThreshold)
                            }
                        }
                        RetrieverOutcome.Ran(results, latency)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        RetrieverOutcome.Failed(e.message ?: "Image retrieval failed")
                    }
                }
            }

            val bm25Outcome = bm25Deferred.await()
            val bm25List = (bm25Outcome as? RetrieverOutcome.Ran)?.candidates.orEmpty()

            val denseOutcome = if (!config.enableDense) {
                RetrieverOutcome.Skipped("Disabled by config")
            } else if (denseRetriever == null) {
                RetrieverOutcome.Failed("DenseRetriever is unavailable")
            } else if (config.denseSkipEnabled && denseRetriever.shouldSkipDense(bm25List, config.denseSkipThreshold)) {
                RetrieverOutcome.Skipped("BM25 score >= ${config.denseSkipThreshold} (skip threshold reached)")
            } else {
                try {
                    val activeDenseRetriever = when {
                        config.denseIndexType == DenseIndexType.AUTO ->
                            autoDenseRetriever ?: exactMemoryDenseRetriever ?: exactDenseRetriever ?: denseRetriever
                        config.denseIndexType == DenseIndexType.EXACT && exactDenseRetriever != null -> exactDenseRetriever
                        config.denseIndexType == DenseIndexType.EXACT_MEMORY -> exactMemoryDenseRetriever ?: exactDenseRetriever ?: denseRetriever
                        else -> denseRetriever
                    }
                    val vIndex = activeDenseRetriever.getVectorIndex()
                    if (vIndex is LshVectorIndex) {
                        vIndex.adaptiveLsh = config.adaptiveLsh
                        vIndex.candidateCapOverride = config.lshCandidateCap
                    }
                    val denseTextQuery = if (config.denseQueryMode == DenseQueryMode.RAW) {
                        QueryProcessor.rawDenseQuery(rawQuery)
                    } else {
                        denseQuery
                    }
                    val (results, latency) = measureSuspendTime("Dense") {
                        activeDenseRetriever.search(denseTextQuery, config.denseTopK)
                    }
                    denseLatencyMs = latency
                    RetrieverOutcome.Ran(results, latency)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    RetrieverOutcome.Failed(e.message ?: "Dense retrieval failed")
                }
            }

            val imageOutcome = imageDeferred.await()
            val imageList = (imageOutcome as? RetrieverOutcome.Ran)?.candidates.orEmpty()
            val denseList = (denseOutcome as? RetrieverOutcome.Ran)?.candidates.orEmpty()

            val outcomeMap = mapOf(
                RetrieverKind.BM25 to bm25Outcome,
                RetrieverKind.DENSE to denseOutcome,
                RetrieverKind.IMAGE to imageOutcome
            )

            Tuple4(outcomeMap, bm25List, denseList, imageList)
        }

        // 3. Fusion / Ranking / Reranking
        var fusionLatencyMs = 0L
        var rerankLatencyMs = 0L
        var rerankTimedOut = false

        val preAggregatedResults: List<SearchResult> = if (config.enableBm25 && !config.enableDense && !config.enableImage) {
            // BM25-only arm
            bm25Results.take(config.bm25TopK)
        } else if (!config.enableBm25 && config.enableDense && !config.enableImage) {
            // Dense-only arm
            denseResults.take(config.denseTopK)
        } else if (!config.enableBm25 && !config.enableDense && config.enableImage) {
            // Image-only arm
            imageResults.take(config.imageTopK)
        } else {
            // Hybrid fusion across backends
            val bm25Map = bm25Results.associateBy { it.entityType to (if (it.stableKey.isNotBlank()) it.stableKey else it.id.toString()) }
            val denseMap = denseResults.associateBy { it.entityType to (if (it.stableKey.isNotBlank()) it.stableKey else it.id.toString()) }
            val imageMap = imageResults.associateBy { it.entityType to (if (it.stableKey.isNotBlank()) it.stableKey else it.id.toString()) }
            val allKeys = (bm25Map.keys + denseMap.keys + imageMap.keys).distinct()

            val candidates = allKeys.mapNotNull { key ->
                val bm25 = bm25Map[key]
                val dense = denseMap[key]
                val img = imageMap[key]
                val source = img ?: dense ?: bm25
                source?.let {
                    FusionCandidate(
                        id = it.id,
                        title = it.title,
                        snippet = it.snippet,
                        filePath = it.filePath,
                        fileType = it.fileType,
                        modifiedAt = it.modifiedAt,
                        sizeBytes = it.sizeBytes,
                        bm25Score = bm25?.score?.toDouble(),
                        denseScore = (img ?: dense)?.score?.toDouble(),
                        embedding = (img ?: dense)?.embedding,
                        entityType = it.entityType,
                        stableKey = it.stableKey
                    )
                }
            }

            val (fused, fLatency) = measureSuspendTime("Fusion") {
                fusionRanker.rank(
                    query = query,
                    results = candidates,
                    mode = config.fusionMode,
                    typePriorEnabled = config.typePriorEnabled,
                    perSpaceNorm = config.perSpaceNorm
                )
            }
            fusionLatencyMs = fLatency

            if (config.rerankBeforeDiversify) {
                // CLEAN pipeline: Rerank top-k first, then optionally diversify
                val candidatesToRerank = fused.take(config.rerankTopK).map {
                    SearchResult(
                        id = it.id,
                        title = it.title,
                        snippet = it.snippet,
                        filePath = it.filePath,
                        fileType = it.fileType,
                        score = it.finalScore.toFloat(),
                        modifiedAt = it.modifiedAt,
                        embedding = it.embedding,
                        sizeBytes = it.sizeBytes,
                        entityType = it.entityType,
                        stableKey = it.stableKey
                    )
                }

                val reranked = if (config.enableRerank && candidatesToRerank.isNotEmpty() &&
                    (config.enableBm25 && (config.enableDense || config.enableImage))) {
                    val (rerankOutcome, rLatency) = measureSuspendTime("Rerank") {
                        crossEncoderReranker.rerankDetailed(
                            query = query,
                            candidates = candidatesToRerank,
                            topK = config.rerankTopK,
                            returnTopK = config.rerankTopK,
                            crossWeight = config.crossWeight,
                            initialWeight = config.initialWeight,
                            tokenizerMode = config.tokenizerMode,
                            maxRerankTimeMs = config.maxRerankTimeMs
                        )
                    }
                    rerankLatencyMs = rLatency
                    rerankTimedOut = rerankOutcome.timedOut
                    rerankOutcome.results
                } else {
                    candidatesToRerank
                }

                if (config.enableDiversification) {
                    val candidatesForDiversify = reranked.map {
                        FusionCandidate(
                            id = it.id,
                            title = it.title,
                            snippet = it.snippet,
                            filePath = it.filePath,
                            fileType = it.fileType,
                            modifiedAt = it.modifiedAt,
                            sizeBytes = it.sizeBytes,
                            finalScore = it.score.toDouble(),
                            embedding = it.embedding,
                            entityType = it.entityType,
                            stableKey = it.stableKey
                        )
                    }
                    fusionRanker.diversify(candidatesForDiversify, limit = config.returnTopK).map {
                        SearchResult(
                            id = it.id,
                            title = it.title,
                            snippet = it.snippet,
                            filePath = it.filePath,
                            fileType = it.fileType,
                            score = it.finalScore.toFloat(),
                            modifiedAt = it.modifiedAt,
                            embedding = it.embedding,
                            sizeBytes = it.sizeBytes,
                            entityType = it.entityType,
                            stableKey = it.stableKey
                        )
                    }
                } else {
                    reranked.take(config.returnTopK)
                }
            } else {
                // LEGACY pipeline: diversify truncates to 20 first, then rerank runs on those 20
                val diversified = if (config.enableDiversification) {
                    fusionRanker.diversify(fused, limit = 20)
                } else {
                    fused
                }

                val candidatesToRerank = diversified.map {
                    SearchResult(
                        id = it.id,
                        title = it.title,
                        snippet = it.snippet,
                        filePath = it.filePath,
                        fileType = it.fileType,
                        score = it.finalScore.toFloat(),
                        modifiedAt = it.modifiedAt,
                        embedding = it.embedding,
                        sizeBytes = it.sizeBytes,
                        entityType = it.entityType,
                        stableKey = it.stableKey
                    )
                }

                if (config.enableRerank && candidatesToRerank.isNotEmpty() &&
                    (config.enableBm25 && (config.enableDense || config.enableImage))) {
                    val (rerankOutcome, rLatency) = measureSuspendTime("Rerank") {
                        crossEncoderReranker.rerankDetailed(
                            query = query,
                            candidates = candidatesToRerank.take(config.rerankTopK),
                            topK = config.rerankTopK,
                            returnTopK = config.returnTopK,
                            crossWeight = config.crossWeight,
                            initialWeight = config.initialWeight,
                            tokenizerMode = config.tokenizerMode,
                            maxRerankTimeMs = config.maxRerankTimeMs
                        )
                    }
                    rerankLatencyMs = rLatency
                    rerankTimedOut = rerankOutcome.timedOut
                    rerankOutcome.results
                } else {
                    candidatesToRerank.take(config.returnTopK)
                }
            }
        }

        // 4. Result Aggregation to Files
        val aggregatedResults = ResultAggregator.aggregateToFiles(preAggregatedResults, query)

        val totalLatencyMs = System.currentTimeMillis() - totalStart

        SearchOutcome(
            query = query,
            config = config,
            results = aggregatedResults,
            candidatesByRetriever = candidatesByRetriever,
            totalLatencyMs = totalLatencyMs,
            bm25LatencyMs = bm25LatencyMs,
            denseLatencyMs = denseLatencyMs,
            fusionLatencyMs = fusionLatencyMs,
            rerankLatencyMs = rerankLatencyMs,
            indexGeneration = indexGenerationProvider(),
            rerankTimedOut = rerankTimedOut
        )
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
