package com.augt.localseek.search.vector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.augt.localseek.data.ChunkDao
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptive configuration for LSH based on dataset size and device state.
 */
data class LshConfig(
    val numTables: Int,
    val numHashBits: Int,
    val projectionDim: Int,
    val searchCandidates: Int,
    val memoryMode: MemoryMode
) {
    enum class MemoryMode {
        IN_MEMORY,
        STREAMING
    }

    companion object {
        fun forDatasetSize(size: Int, batteryLevel: Int = 100): LshConfig {
            return when {
                size < 10_000 -> LshConfig(
                    numTables = 5,
                    numHashBits = 10,
                    projectionDim = 48,
                    searchCandidates = 80,
                    memoryMode = MemoryMode.IN_MEMORY
                )
                size < 50_000 -> LshConfig(
                    numTables = 10,
                    numHashBits = 12,
                    projectionDim = 64,
                    searchCandidates = 100,
                    memoryMode = MemoryMode.IN_MEMORY
                )
                size < 200_000 -> LshConfig(
                    numTables = 15,
                    numHashBits = 14,
                    projectionDim = 80,
                    searchCandidates = 120,
                    memoryMode = if (batteryLevel > 50) MemoryMode.IN_MEMORY else MemoryMode.STREAMING
                )
                else -> LshConfig(
                    numTables = 20,
                    numHashBits = 16,
                    projectionDim = 96,
                    searchCandidates = 150,
                    memoryMode = MemoryMode.STREAMING
                )
            }
        }

        fun forBatteryLevel(baseConfig: LshConfig, batteryLevel: Int): LshConfig {
            return when {
                batteryLevel < 20 -> baseConfig.copy(
                    numTables = maxOf(3, baseConfig.numTables / 3),
                    searchCandidates = baseConfig.searchCandidates / 2,
                    memoryMode = MemoryMode.STREAMING
                )
                batteryLevel < 50 -> baseConfig.copy(
                    numTables = maxOf(5, baseConfig.numTables / 2),
                    searchCandidates = (baseConfig.searchCandidates * 0.7f).toInt()
                )
                else -> baseConfig
            }
        }
    }
}

class BatteryMonitor(private val context: Context) {
    fun getCurrentBatteryLevel(): Int {
        val batteryStatus = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level >= 0 && scale > 0) {
            ((level / scale.toFloat()) * 100).toInt()
        } else {
            100
        }
    }
}

/**
 * Pure Kotlin ANN based on random-projection LSH.
 * This avoids native FAISS/JNI dependencies while keeping dense retrieval scalable.
 */
class LshIndexManager(private val context: Context) {

    companion object {
        private const val TAG = "LshIndexManager"
        private const val INDEX_FILE = "lsh_index.bin"
        private const val INDEX_VERSION = 1

        private const val EMBEDDING_DIM = 384
    }

    data class IndexStats(
        val totalVectors: Int,
        val numTables: Int,
        val avgBucketSize: Float,
        val buildTimeMs: Long,
        val sizeBytes: Long
    )

    data class SearchResult(
        val chunkId: Long,
        val score: Float
    )

    private var config: LshConfig = LshConfig.forDatasetSize(0)
    private val batteryMonitor = BatteryMonitor(context)

    private var hashTables: Array<MutableMap<Int, MutableList<Long>>> = emptyArray()
    private val embeddingStore = mutableMapOf<Long, FloatArray>()
    private var projectionMatrices: Array<Array<FloatArray>> = emptyArray()

    @Volatile
    private var isInitialized = false
    private var indexedVectorCount = 0

    init {
        initializeAdaptiveStructures(config)
    }

    private fun initializeAdaptiveStructures(newConfig: LshConfig) {
        config = newConfig
        hashTables = Array(config.numTables) { mutableMapOf() }
        projectionMatrices = Array(config.numTables) {
            Array(config.projectionDim) { FloatArray(EMBEDDING_DIM) }
        }
        initializeProjections()
    }

    private fun initializeProjections() {
        val random = Random(42)
        for (table in 0 until config.numTables) {
            for (i in 0 until config.projectionDim) {
                for (j in 0 until EMBEDDING_DIM) {
                    projectionMatrices[table][i][j] = random.nextFloat() * 2f - 1f
                }
            }
        }
    }

    suspend fun buildIndex(
        embeddings: List<Pair<Long, FloatArray>>,
        customConfig: LshConfig? = null
    ): IndexStats = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        try {
            val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
            val baseConfig = LshConfig.forDatasetSize(embeddings.size, batteryLevel)
            val adaptiveConfig = customConfig ?: LshConfig.forBatteryLevel(baseConfig, batteryLevel)

            initializeAdaptiveStructures(adaptiveConfig)
            hashTables.forEach { it.clear() }

            Log.d(
                TAG,
                "Adaptive LSH Config: size=${embeddings.size} battery=${batteryLevel}% tables=${config.numTables} bits=${config.numHashBits} mode=${config.memoryMode}"
            )

            val validEmbeddings = embeddings.filter { it.second.size == EMBEDDING_DIM }
            when (config.memoryMode) {
                LshConfig.MemoryMode.IN_MEMORY -> {
                    embeddingStore.clear()
                    validEmbeddings.forEach { (chunkId, embedding) ->
                        addVector(chunkId, embedding, updateStore = true)
                    }
                }
                LshConfig.MemoryMode.STREAMING -> {
                    embeddingStore.clear()
                    validEmbeddings.forEach { (chunkId, embedding) ->
                        addVector(chunkId, embedding, updateStore = false)
                    }
                    Log.d(TAG, "Streaming mode enabled: embeddings are not cached in memory")
                }
            }

            indexedVectorCount = validEmbeddings.size
            isInitialized = indexedVectorCount > 0

            val totalBuckets = hashTables.sumOf { it.size }
            val avgBucketSize = if (totalBuckets == 0) 0f else indexedVectorCount.toFloat() / totalBuckets
            val buildTimeMs = System.currentTimeMillis() - startTime

            saveIndex(validEmbeddings)

            Log.i(
                TAG,
                """
                ========================================
                ADAPTIVE LSH INDEX BUILT
                ========================================
                Dataset size: ${embeddings.size}
                Battery level: ${batteryMonitor.getCurrentBatteryLevel()}%

                Configuration:
                - Tables: ${config.numTables}
                - Hash bits: ${config.numHashBits}
                - Projection dim: ${config.projectionDim}
                - Memory mode: ${config.memoryMode}
                - Search candidates: ${config.searchCandidates}

                Performance:
                - Build time: ${buildTimeMs}ms
                - Avg bucket size: ${avgBucketSize.toInt()}
                - Index size: ${getIndexSize() / 1024}KB
                ========================================
                """.trimIndent()
            )

            IndexStats(
                totalVectors = indexedVectorCount,
                numTables = config.numTables,
                avgBucketSize = avgBucketSize,
                buildTimeMs = buildTimeMs,
                sizeBytes = getIndexSize()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build adaptive LSH index", e)
            throw e
        }
    }

    suspend fun rebuildFromDatabase(chunkDao: ChunkDao): IndexStats = withContext(Dispatchers.IO) {
        val embeddings = mutableListOf<Pair<Long, FloatArray>>()
        var offset = 0
        val pageSize = 1000

        while (true) {
            val page = chunkDao.getEmbeddingsPage(limit = pageSize, offset = offset)
            if (page.isEmpty()) break
            page.forEach { embeddings.add(it.id to it.embedding) }
            offset += pageSize
        }

        buildIndex(embeddings)
    }

    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 50,
        chunkDao: ChunkDao? = null
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        if (!isInitialized || queryEmbedding.size != EMBEDDING_DIM || topK <= 0) {
            return@withContext emptyList()
        }

        val batteryLevel = batteryMonitor.getCurrentBatteryLevel()
        val runtimeConfig = LshConfig.forBatteryLevel(config, batteryLevel)
        val activeTables = minOf(runtimeConfig.numTables, hashTables.size)

        val candidates = linkedSetOf<Long>()
        for (tableIdx in 0 until activeTables) {
            val hash = computeHash(queryEmbedding, tableIdx)
            hashTables[tableIdx][hash]?.let { candidates.addAll(it) }
        }

        val limitedCandidates = candidates.take(runtimeConfig.searchCandidates)
        Log.d(TAG, "Found ${limitedCandidates.size} candidates (limited from ${candidates.size})")

        val mode = when {
            runtimeConfig.memoryMode == LshConfig.MemoryMode.STREAMING -> LshConfig.MemoryMode.STREAMING
            config.memoryMode == LshConfig.MemoryMode.STREAMING -> LshConfig.MemoryMode.STREAMING
            else -> LshConfig.MemoryMode.IN_MEMORY
        }

        val scored = when (mode) {
            LshConfig.MemoryMode.IN_MEMORY -> {
                limitedCandidates.mapNotNull { chunkId ->
                    val embedding = embeddingStore[chunkId] ?: return@mapNotNull null
                    SearchResult(chunkId = chunkId, score = cosineSimilarity(queryEmbedding, embedding))
                }
            }
            LshConfig.MemoryMode.STREAMING -> {
                if (chunkDao == null) {
                    Log.w(TAG, "Streaming mode requires ChunkDao")
                    return@withContext emptyList()
                }
                fetchAndScoreCandidates(limitedCandidates, queryEmbedding, chunkDao)
            }
        }

        val topResults = scored.sortedByDescending { it.score }.take(topK)
        Log.d(TAG, "Adaptive LSH search: results=${topResults.size} battery=${batteryLevel}% mode=$mode")
        topResults
    }

    suspend fun addVectors(newEmbeddings: List<Pair<Long, FloatArray>>) = withContext(Dispatchers.Default) {
        if (newEmbeddings.isEmpty()) return@withContext

        newEmbeddings.forEach { (chunkId, embedding) ->
            if (embedding.size == EMBEDDING_DIM) {
                val keepInMemory = config.memoryMode == LshConfig.MemoryMode.IN_MEMORY
                addVector(chunkId, embedding, updateStore = keepInMemory)
            }
        }

        indexedVectorCount += newEmbeddings.count { it.second.size == EMBEDDING_DIM }
        isInitialized = indexedVectorCount > 0
        saveIndex()
    }

    suspend fun loadIndex(): Boolean = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, INDEX_FILE)
        if (!file.exists()) return@withContext false

        return@withContext try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val version = input.readInt()
                if (version != INDEX_VERSION) {
                    Log.w(TAG, "Skipping stale LSH index version=$version")
                    return@withContext false
                }

                val persistedConfig = LshConfig(
                    numTables = input.readInt(),
                    numHashBits = input.readInt(),
                    projectionDim = input.readInt(),
                    searchCandidates = input.readInt(),
                    memoryMode = LshConfig.MemoryMode.entries[input.readInt().coerceIn(0, LshConfig.MemoryMode.entries.lastIndex)]
                )
                initializeAdaptiveStructures(persistedConfig)

                val count = input.readInt()
                embeddingStore.clear()
                hashTables.forEach { it.clear() }
                indexedVectorCount = count

                repeat(count) {
                    val chunkId = input.readLong()
                    val embedding = FloatArray(EMBEDDING_DIM)
                    for (i in 0 until EMBEDDING_DIM) {
                        embedding[i] = input.readFloat()
                    }
                    if (config.memoryMode == LshConfig.MemoryMode.IN_MEMORY) {
                        embeddingStore[chunkId] = embedding
                    }
                    addVector(chunkId, embedding, updateStore = false)
                }
            }

            isInitialized = indexedVectorCount > 0
            Log.d(TAG, "Loaded adaptive LSH index vectors=$indexedVectorCount mode=${config.memoryMode}")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LSH index", e)
            false
        }
    }

    fun clearIndex() {
        hashTables.forEach { it.clear() }
        embeddingStore.clear()
        indexedVectorCount = 0
        isInitialized = false
        File(context.filesDir, INDEX_FILE).delete()
    }

    private suspend fun saveIndex(sourceEmbeddings: List<Pair<Long, FloatArray>>? = null) = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, INDEX_FILE)
        try {
            val serializable = sourceEmbeddings ?: embeddingStore.entries.map { it.key to it.value }
            if (serializable.isEmpty() && indexedVectorCount > 0) {
                Log.w(TAG, "Skipping index persistence: streaming mode has no in-memory vectors for incremental save")
                return@withContext
            }
            DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
                output.writeInt(INDEX_VERSION)
                output.writeInt(config.numTables)
                output.writeInt(config.numHashBits)
                output.writeInt(config.projectionDim)
                output.writeInt(config.searchCandidates)
                output.writeInt(config.memoryMode.ordinal)
                output.writeInt(serializable.size)
                serializable.forEach { (chunkId, embedding) ->
                    output.writeLong(chunkId)
                    embedding.forEach { output.writeFloat(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save LSH index", e)
        }
    }

    private fun addVector(chunkId: Long, embedding: FloatArray, updateStore: Boolean) {
        if (updateStore) {
            embeddingStore[chunkId] = embedding
        }

        for (tableIdx in 0 until config.numTables) {
            val hash = computeHash(embedding, tableIdx)
            hashTables[tableIdx].getOrPut(hash) { mutableListOf() }.add(chunkId)
        }
    }

    private fun computeHash(embedding: FloatArray, tableIdx: Int): Int {
        val matrix = projectionMatrices[tableIdx]
        var hash = 0

        for (bit in 0 until minOf(config.numHashBits, config.projectionDim)) {
            var dot = 0f
            for (d in 0 until EMBEDDING_DIM) {
                dot += embedding[d] * matrix[bit][d]
            }
            if (dot > 0f) {
                hash = hash or (1 shl bit)
            }
        }

        return hash
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }

        if (normA <= 0f || normB <= 0f) return 0f
        return (dot / (kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble()))).toFloat()
    }

    private suspend fun fetchAndScoreCandidates(
        candidateIds: List<Long>,
        queryEmbedding: FloatArray,
        chunkDao: ChunkDao
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        candidateIds.mapNotNull { chunkId ->
            try {
                val embedding = chunkDao.getChunkById(chunkId)?.embedding ?: return@mapNotNull null
                SearchResult(chunkId, cosineSimilarity(queryEmbedding, embedding))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch embedding for chunkId=$chunkId", e)
                null
            }
        }
    }

    private fun getIndexSize(): Long = File(context.filesDir, INDEX_FILE).takeIf { it.exists() }?.length() ?: 0L
}

