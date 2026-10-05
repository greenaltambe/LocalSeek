package com.augt.localseek.di

import android.content.Context
import com.augt.localseek.data.AppDatabase
import com.augt.localseek.ml.ModelRegistry
import com.augt.localseek.retrieval.BM25Retriever
import com.augt.localseek.retrieval.CrossEncoderReranker
import com.augt.localseek.retrieval.DenseRetriever
import com.augt.localseek.retrieval.ImageRetriever
import com.augt.localseek.eval.BenchmarkRunner
import com.augt.localseek.search.SearchEngine
import com.augt.localseek.search.query.QueryProcessor
import com.augt.localseek.search.vector.AutoVectorIndex
import com.augt.localseek.search.vector.ImageBruteForceVectorIndex
import com.augt.localseek.search.vector.IndexGeneration
import com.augt.localseek.search.vector.LshIndexManager
import com.augt.localseek.search.vector.LshVectorIndex
import com.augt.localseek.search.vector.VectorIndex
import com.augt.localseek.ui.settings.SettingsRepository

/**
 * Process-wide application dependency container for LocalSeek.
 *
 * Owned by [com.augt.localseek.LocalSeekApplication]. Shared identically across the UI process
 * (SearchViewModel) and background worker execution (IndexWorker).
 */
class AppContainer(
    val context: Context,
    val database: AppDatabase = AppDatabase.getInstance(context.applicationContext),
    val modelRegistry: ModelRegistry = ModelRegistry(context.applicationContext)
) : AutoCloseable {

    val applicationContext: Context = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }

    /**
     * Only the benchmark LSH arms (E3, E5, E12) use this. Production never builds, loads or writes the LSH index; the manager
     * reads an existing lsh_index.bin when a benchmark arm asks for it and otherwise builds in memory (it never writes the file).
     */
    val lshIndexManager: LshIndexManager by lazy {
        LshIndexManager(applicationContext)
    }

    /** Changes after every indexing run; keys the query cache and the in-memory dense indexes. Needs no LSH index. */
    val indexGeneration: IndexGeneration = IndexGeneration()

    val denseVectorIndex: VectorIndex by lazy {
        LshVectorIndex(lshIndexManager, database.chunkDao())
    }

    val imageVectorIndex: ImageBruteForceVectorIndex by lazy {
        ImageBruteForceVectorIndex(database.imageDao())
    }

    val bm25Retriever: BM25Retriever by lazy {
        BM25Retriever(applicationContext)
    }

    val denseRetriever: DenseRetriever by lazy {
        DenseRetriever(
            context = applicationContext,
            encoder = modelRegistry.denseEncoder,
            indexManager = lshIndexManager,
            vectorIndex = denseVectorIndex
        )
    }

    val imageRetriever: ImageRetriever by lazy {
        ImageRetriever(
            context = applicationContext,
            clipTextEncoder = modelRegistry.clipTextEncoder,
            imageVectorIndex = imageVectorIndex
        )
    }

    val crossEncoderReranker: CrossEncoderReranker by lazy {
        CrossEncoderReranker(
            context = applicationContext,
            crossEncoder = modelRegistry.crossEncoder
        )
    }

    val exactVectorIndex: VectorIndex by lazy {
        com.augt.localseek.search.vector.BruteForceVectorIndex(database.chunkDao())
    }

    val exactDenseRetriever: DenseRetriever by lazy {
        DenseRetriever(
            context = applicationContext,
            encoder = modelRegistry.denseEncoder,
            indexManager = lshIndexManager,
            vectorIndex = exactVectorIndex
        )
    }

    val exactMemoryVectorIndex: VectorIndex by lazy {
        com.augt.localseek.search.vector.ExactMemoryVectorIndex(
            chunkDao = database.chunkDao(),
            fallback = exactVectorIndex,
            versionProvider = { indexGeneration.current * 1_000_003L + database.chunkDao().countAllChunks() },
            debugLog = { if (com.augt.localseek.BuildConfig.DEBUG) android.util.Log.d("ExactMemoryIndex", it) }
        )
    }

    val exactMemoryDenseRetriever: DenseRetriever by lazy {
        DenseRetriever(
            context = applicationContext,
            encoder = modelRegistry.denseEncoder,
            indexManager = lshIndexManager,
            vectorIndex = exactMemoryVectorIndex
        )
    }

    /** The shipped dense index: exact in-memory up to 50,000 chunk vectors, binary shortlist + float rescoring above. */
    val autoVectorIndex: AutoVectorIndex by lazy {
        AutoVectorIndex(
            chunkDao = database.chunkDao(),
            exact = exactMemoryVectorIndex,
            fallback = exactVectorIndex,
            versionProvider = { indexGeneration.current * 1_000_003L + database.chunkDao().countAllChunks() },
            debugLog = { if (com.augt.localseek.BuildConfig.DEBUG) android.util.Log.d("AutoVectorIndex", it) }
        )
    }

    val autoDenseRetriever: DenseRetriever by lazy {
        DenseRetriever(
            context = applicationContext,
            encoder = modelRegistry.denseEncoder,
            indexManager = lshIndexManager,
            vectorIndex = autoVectorIndex
        )
    }

    val queryProcessor: QueryProcessor by lazy {
        QueryProcessor(
            context = applicationContext,
            encoder = modelRegistry.denseEncoder
        )
    }

    val imageFilenameRetriever: com.augt.localseek.retrieval.ImageFilenameRetriever by lazy {
        com.augt.localseek.retrieval.ImageFilenameRetriever(database.imageDao())
    }

    val searchEngine: SearchEngine by lazy {
        SearchEngine(
            bm25Retriever = bm25Retriever,
            denseRetriever = denseRetriever,
            exactDenseRetriever = exactDenseRetriever,
            imageRetriever = imageRetriever,
            crossEncoderReranker = crossEncoderReranker,
            queryProcessor = queryProcessor,
            indexGenerationProvider = { indexGeneration.current },
            imageFilenameRetriever = imageFilenameRetriever,
            exactMemoryDenseRetriever = exactMemoryDenseRetriever,
            autoDenseRetriever = autoDenseRetriever
        )
    }

    val benchmarkRunner: BenchmarkRunner by lazy {
        BenchmarkRunner(
            context = applicationContext,
            searchEngine = searchEngine,
            database = database,
            crossEncoderReranker = crossEncoderReranker
        )
    }

    override fun close() {
        modelRegistry.close()
        lshIndexManager.clearIndex()
    }
}
