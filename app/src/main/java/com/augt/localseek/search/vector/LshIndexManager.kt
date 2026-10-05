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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
    val memoryMode: MemoryMode,
    val probeRadius: Int = 0
) {
    enum class MemoryMode {
        IN_MEMORY,
        STREAMING
    }

    companion object {
        fun forDatasetSize(size: Int, batteryLevel: Int = 100): LshConfig {
            val targetOccupancy = 25.0
            // Continuous bit-depth scaling: log2(N / target_occupancy)
            val derivedBits = if (size > 0) {
                kotlin.math.ceil(kotlin.math.log2(size.toDouble() / targetOccupancy)).toInt().coerceIn(6, 16)
            } else 10

            return when {
                size < 10_000 -> LshConfig(
                    numTables = 5,
                    numHashBits = derivedBits,
                    projectionDim = 48,
                    searchCandidates = 80,
                    memoryMode = MemoryMode.IN_MEMORY,
                    probeRadius = 1
                )
                size < 50_000 -> LshConfig(
                    numTables = 10,
                    numHashBits = derivedBits,
                    projectionDim = 64,
                    searchCandidates = 100,
                    memoryMode = MemoryMode.IN_MEMORY,
                    probeRadius = 1
                )
                size < 200_000 -> LshConfig(
                    numTables = 15,
                    numHashBits = derivedBits,
                    projectionDim = 80,
                    searchCandidates = 120,
                    memoryMode = if (batteryLevel > 50) MemoryMode.IN_MEMORY else MemoryMode.STREAMING,
                    probeRadius = 1
                )
                else -> LshConfig(
                    numTables = 20,
                    numHashBits = derivedBits,
                    projectionDim = 96,
                    searchCandidates = 150,
                    memoryMode = MemoryMode.STREAMING,
                    probeRadius = 1
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

open class BatteryMonitor(private val context: Context) {
    open fun getCurrentBatteryLevel(): Int {
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
 * Immutable snapshot of the LSH vector index.
 * Thread-safe for concurrent readers without locks.
 */
class LshSnapshot(
    val config: LshConfig,
    val projectionMatrices: Array<Array<FloatArray>>,
    val hashTables: Array<Map<Int, List<Long>>>,
    val embeddingStore: Map<Long, FloatArray>,
    val indexedVectorCount: Int,
    val generation: Long
) {
    val isInitialized: Boolean get() = indexedVectorCount > 0
}

/**
 * Pure Kotlin ANN based on random-projection LSH with atomic snapshot publication.
 *
 * Concurrency guarantees:
 * - Readers (searches) observe an immutable [LshSnapshot] swapped via [AtomicReference].
 * - Index rebuilding constructs candidate structures off to the side and swaps them atomically.
 * - Monotonic [indexGeneration] is incremented upon every snapshot publication.
 */
class LshIndexManager(
    private val context: Context,
    /**
     * When false (the default) the manager only READS an existing lsh_index.bin: a build lives in memory and nothing is written
     * or deleted on disk, so no code path can overwrite the index file that is already on a device. Only tests that exercise
     * persistence pass true (with a private filesDir).
     */
    private val persistIndex: Boolean = false
) {

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

    internal var batteryMonitor = BatteryMonitor(context)

    private val currentSnapshot = AtomicReference<LshSnapshot>(
        createEmptySnapshot(LshConfig.forDatasetSize(0), 0L)
    )
    private val generationCounter = AtomicLong(0L)
    private val mutationLock = Any()

    val indexGeneration: Long
        get() = currentSnapshot.get().generation

    val isInitialized: Boolean
        get() = currentSnapshot.get().isInitialized

    val indexedVectorCount: Int
        get() = currentSnapshot.get().indexedVectorCount

    val currentConfig: LshConfig
        get() = currentSnapshot.get().config

    fun getSnapshot(): LshSnapshot = currentSnapshot.get()

    private fun generateProjections(targetConfig: LshConfig): Array<Array<FloatArray>> {
        val matrices = Array(targetConfig.numTables) {
            Array(targetConfig.projectionDim) { FloatArray(EMBEDDING_DIM) }
        }
        val random = Random(42)
        for (table in 0 until targetConfig.numTables) {
            for (i in 0 until targetConfig.projectionDim) {
                for (j in 0 until EMBEDDING_DIM) {
                    matrices[table][i][j] = random.nextFloat() * 2f - 1f
                }
            }
        }
        return matrices
    }

    private fun createEmptySnapshot(targetConfig: LshConfig, generation: Long): LshSnapshot {
        val projections = generateProjections(targetConfig)
        val tables = Array<Map<Int, List<Long>>>(targetConfig.numTables) { emptyMap() }
        return LshSnapshot(
            config = targetConfig,
            projectionMatrices = projections,
            hashTables = tables,
            embeddingStore = emptyMap(),
            indexedVectorCount = 0,
            generation = generation
        )
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

            val validEmbeddings = embeddings.filter { it.second.size == EMBEDDING_DIM }

            // 1. Build projections and hash tables completely off to the side
            val projectionMatrices = generateProjections(adaptiveConfig)
            val localHashTables = Array(adaptiveConfig.numTables) { mutableMapOf<Int, MutableList<Long>>() }
            val localEmbeddingStore = HashMap<Long, FloatArray>(
                if (adaptiveConfig.memoryMode == LshConfig.MemoryMode.IN_MEMORY) validEmbeddings.size else 0
            )

            for ((chunkId, embedding) in validEmbeddings) {
                if (adaptiveConfig.memoryMode == LshConfig.MemoryMode.IN_MEMORY) {
                    localEmbeddingStore[chunkId] = embedding
                }
                for (tableIdx in 0 until adaptiveConfig.numTables) {
                    val hash = computeHash(
                        embedding,
                        projectionMatrices[tableIdx],
                        adaptiveConfig.numHashBits,
                        adaptiveConfig.projectionDim
                    )
                    localHashTables[tableIdx].getOrPut(hash) { mutableListOf() }.add(chunkId)
                }
            }

            // Freeze tables into immutable lists
            val frozenHashTables: Array<Map<Int, List<Long>>> = Array(adaptiveConfig.numTables) { tableIdx ->
                localHashTables[tableIdx].mapValues { it.value.toList() }
            }
            val frozenEmbeddingStore: Map<Long, FloatArray> = localEmbeddingStore.toMap()

            val newGeneration = generationCounter.incrementAndGet()
            val newSnapshot = LshSnapshot(
                config = adaptiveConfig,
                projectionMatrices = projectionMatrices,
                hashTables = frozenHashTables,
                embeddingStore = frozenEmbeddingStore,
                indexedVectorCount = validEmbeddings.size,
                generation = newGeneration
            )

            // Save index to disk
            saveIndex(adaptiveConfig, validEmbeddings, frozenEmbeddingStore)

            // 2. ATOMIC SWAP: Publish the completed immutable snapshot
            currentSnapshot.set(newSnapshot)

            val totalBuckets = frozenHashTables.sumOf { it.size }
            val avgBucketSize = if (totalBuckets == 0) 0f else validEmbeddings.size.toFloat() / totalBuckets
            val buildTimeMs = System.currentTimeMillis() - startTime

            Log.i(
                TAG,
                """
                ========================================
                ADAPTIVE LSH INDEX BUILT (ATOMIC SWAP)
                ========================================
                Dataset size: ${embeddings.size}
                Generation: $newGeneration
                Tables: ${adaptiveConfig.numTables}
                Hash bits: ${adaptiveConfig.numHashBits}
                Mode: ${adaptiveConfig.memoryMode}
                Build time: ${buildTimeMs}ms
                Avg bucket size: ${avgBucketSize.toInt()}
                ========================================
                """.trimIndent()
            )

            IndexStats(
                totalVectors = validEmbeddings.size,
                numTables = adaptiveConfig.numTables,
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
        var lastId = -1L
        val pageSize = 1000

        while (true) {
            val page = chunkDao.getEmbeddingsPage(limit = pageSize, lastId = lastId)
            if (page.isEmpty()) break
            page.forEach {
                embeddings.add(it.id to it.embedding)
                lastId = it.id
            }
        }

        buildIndex(embeddings)
    }

    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 50,
        chunkDao: ChunkDao? = null,
        adaptiveLsh: Boolean = false,
        candidateCapOverride: Int = 0
    ): List<ScoredResult> = withContext(Dispatchers.Default) {
        val snapshot = currentSnapshot.get()
        if (!snapshot.isInitialized || queryEmbedding.size != EMBEDDING_DIM || topK <= 0) {
            return@withContext emptyList()
        }

        val batteryLevel = if (adaptiveLsh) batteryMonitor.getCurrentBatteryLevel() else 100
        val runtimeConfig = if (adaptiveLsh) {
            LshConfig.forBatteryLevel(snapshot.config, batteryLevel)
        } else {
            snapshot.config
        }
        val activeTables = minOf(runtimeConfig.numTables, snapshot.hashTables.size)

        val candidates = linkedSetOf<Long>()
        var probeCount = 0
        for (tableIdx in 0 until activeTables) {
            val baseHash = computeHash(
                queryEmbedding,
                snapshot.projectionMatrices[tableIdx],
                runtimeConfig.numHashBits,
                runtimeConfig.projectionDim
            )

            // Exact match bucket
            snapshot.hashTables[tableIdx][baseHash]?.let { candidates.addAll(it) }

            // Multi-probe (Hamming distance 1)
            if (runtimeConfig.probeRadius >= 1) {
                for (bit in 0 until runtimeConfig.numHashBits) {
                    val probedHash = baseHash xor (1 shl bit)
                    snapshot.hashTables[tableIdx][probedHash]?.let {
                        candidates.addAll(it)
                        probeCount++
                    }
                }
            }
        }

        // candidateCapOverride: 0 = index configuration (default), > 0 = that cap, < 0 = no cap (Phase 2 exploratory arm E3b)
        val limitedCandidates = when {
            candidateCapOverride < 0 -> candidates.toList()
            candidateCapOverride > 0 -> candidates.take(candidateCapOverride)
            else -> candidates.take(runtimeConfig.searchCandidates)
        }

        val mode = when {
            runtimeConfig.memoryMode == LshConfig.MemoryMode.STREAMING -> LshConfig.MemoryMode.STREAMING
            snapshot.config.memoryMode == LshConfig.MemoryMode.STREAMING -> LshConfig.MemoryMode.STREAMING
            else -> LshConfig.MemoryMode.IN_MEMORY
        }

        val scored = when (mode) {
            LshConfig.MemoryMode.IN_MEMORY -> {
                limitedCandidates.mapNotNull { chunkId ->
                    val embedding = snapshot.embeddingStore[chunkId] ?: return@mapNotNull null
                    ScoredResult(id = chunkId, score = cosineSimilarity(queryEmbedding, embedding))
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

        val topResults = scored.sortedWith(
            compareByDescending<ScoredResult> { it.score }
                .thenBy { it.id }
        ).take(topK)
        topResults
    }

    suspend fun addVectors(newEmbeddings: List<Pair<Long, FloatArray>>) = withContext(Dispatchers.Default) {
        val valid = newEmbeddings.filter { it.second.size == EMBEDDING_DIM }
        if (valid.isEmpty()) return@withContext

        synchronized(mutationLock) {
            val oldSnapshot = currentSnapshot.get()
            val config = oldSnapshot.config

            val newEmbeddingStore = HashMap(oldSnapshot.embeddingStore)
            if (config.memoryMode == LshConfig.MemoryMode.IN_MEMORY) {
                valid.forEach { (id, emb) -> newEmbeddingStore[id] = emb }
            }

            val newHashTables = Array(config.numTables) { tableIdx ->
                val oldTable = oldSnapshot.hashTables.getOrNull(tableIdx).orEmpty()
                val mutableTable = oldTable.mapValues { it.value.toMutableList() }.toMutableMap()
                for ((id, emb) in valid) {
                    val hash = computeHash(
                        emb,
                        oldSnapshot.projectionMatrices[tableIdx],
                        config.numHashBits,
                        config.projectionDim
                    )
                    mutableTable.getOrPut(hash) { mutableListOf() }.add(id)
                }
                mutableTable.mapValues { it.value.toList() }
            }

            val newCount = oldSnapshot.indexedVectorCount + valid.size
            val newGeneration = generationCounter.incrementAndGet()
            val newSnapshot = LshSnapshot(
                config = config,
                projectionMatrices = oldSnapshot.projectionMatrices,
                hashTables = newHashTables,
                embeddingStore = newEmbeddingStore,
                indexedVectorCount = newCount,
                generation = newGeneration
            )

            currentSnapshot.set(newSnapshot)
        }
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
                    memoryMode = LshConfig.MemoryMode.entries[input.readInt().coerceIn(0, LshConfig.MemoryMode.entries.lastIndex)],
                    probeRadius = if (input.available() > 0) input.readInt() else 0
                )
                val projectionMatrices = generateProjections(persistedConfig)

                val count = input.readInt()
                val localEmbeddingStore = HashMap<Long, FloatArray>(
                    if (persistedConfig.memoryMode == LshConfig.MemoryMode.IN_MEMORY) count else 0
                )
                val localHashTables = Array(persistedConfig.numTables) { mutableMapOf<Int, MutableList<Long>>() }

                repeat(count) {
                    val chunkId = input.readLong()
                    val embedding = FloatArray(EMBEDDING_DIM)
                    for (i in 0 until EMBEDDING_DIM) {
                        embedding[i] = input.readFloat()
                    }
                    if (persistedConfig.memoryMode == LshConfig.MemoryMode.IN_MEMORY) {
                        localEmbeddingStore[chunkId] = embedding
                    }
                    for (tableIdx in 0 until persistedConfig.numTables) {
                        val hash = computeHash(
                            embedding,
                            projectionMatrices[tableIdx],
                            persistedConfig.numHashBits,
                            persistedConfig.projectionDim
                        )
                        localHashTables[tableIdx].getOrPut(hash) { mutableListOf() }.add(chunkId)
                    }
                }

                val frozenHashTables: Array<Map<Int, List<Long>>> = Array(persistedConfig.numTables) { tableIdx ->
                    localHashTables[tableIdx].mapValues { it.value.toList() }
                }

                val newGeneration = generationCounter.incrementAndGet()
                val newSnapshot = LshSnapshot(
                    config = persistedConfig,
                    projectionMatrices = projectionMatrices,
                    hashTables = frozenHashTables,
                    embeddingStore = localEmbeddingStore.toMap(),
                    indexedVectorCount = count,
                    generation = newGeneration
                )

                currentSnapshot.set(newSnapshot)
            }

            Log.d(TAG, "Loaded adaptive LSH index vectors=$indexedVectorCount generation=$indexGeneration")
            isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LSH index", e)
            false
        }
    }

    /** Drops the in-memory index. The file on disk is deleted only for a manager created with persistIndex = true. */
    fun clearIndex() {
        synchronized(mutationLock) {
            val newGeneration = generationCounter.incrementAndGet()
            currentSnapshot.set(createEmptySnapshot(LshConfig.forDatasetSize(0), newGeneration))
            if (persistIndex) File(context.filesDir, INDEX_FILE).delete()
        }
    }

    private suspend fun saveIndex(
        config: LshConfig,
        validEmbeddings: List<Pair<Long, FloatArray>>,
        embeddingStore: Map<Long, FloatArray>
    ) = withContext(Dispatchers.IO) {
        if (!persistIndex) return@withContext
        val file = File(context.filesDir, INDEX_FILE)
        try {
            val serializable = if (validEmbeddings.isNotEmpty()) {
                validEmbeddings
            } else {
                embeddingStore.entries.map { it.key to it.value }
            }

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
                output.writeInt(config.probeRadius)
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

    private fun computeHash(
        embedding: FloatArray,
        matrix: Array<FloatArray>,
        numHashBits: Int,
        projectionDim: Int
    ): Int {
        var hash = 0
        for (bit in 0 until minOf(numHashBits, projectionDim)) {
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
    ): List<ScoredResult> = withContext(Dispatchers.IO) {
        candidateIds.mapNotNull { chunkId ->
            try {
                val embedding = chunkDao.getChunkById(chunkId)?.embedding ?: return@mapNotNull null
                ScoredResult(chunkId, cosineSimilarity(queryEmbedding, embedding))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch embedding for chunkId=$chunkId", e)
                null
            }
        }
    }

    private fun getIndexSize(): Long = File(context.filesDir, INDEX_FILE).takeIf { it.exists() }?.length() ?: 0L
}
