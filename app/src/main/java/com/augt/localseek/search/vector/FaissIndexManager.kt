package com.augt.localseek.search.vector

import android.content.Context
import android.util.Log
import com.augt.localseek.data.ChunkDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FaissIndexManager(private val context: Context) {

    companion object {
        private const val TAG = "FaissIndexManager"
        private const val INDEX_FILE = "faiss_index.bin"
        private const val METADATA_FILE = "faiss_metadata.bin"
        private const val EMBEDDING_DIM = 384
        private const val NLIST = 100
        private const val M = 8
        private const val NBITS = 8
        private const val INDEX_VERSION = 1
    }

    data class IndexStats(
        val totalVectors: Int,
        val indexType: String,
        val buildTimeMs: Long,
        val sizeBytes: Long
    )

    data class SearchResult(
        val chunkId: Long,
        val score: Float
    )

    private var faissIndex: Any? = null
    private val idMapping = ConcurrentHashMap<Long, Long>()
    private val vectorFallback = mutableListOf<Pair<Long, FloatArray>>()
    private var faissEnabled = false

    suspend fun buildIndex(embeddings: List<Pair<Long, FloatArray>>): IndexStats = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (embeddings.isEmpty()) {
            clearIndex()
            return@withContext IndexStats(0, "EMPTY", 0, 0)
        }

        faissEnabled = tryBuildFaiss(embeddings)
        if (!faissEnabled) {
            vectorFallback.clear()
            vectorFallback.addAll(embeddings)
            idMapping.clear()
            embeddings.forEachIndexed { idx, (chunkId, _) -> idMapping[idx.toLong()] = chunkId }
            persistFallback(embeddings)
            Log.w(TAG, "FAISS unavailable at runtime, using in-memory fallback index")
        } else {
            saveIndex()
        }

        val buildTime = System.currentTimeMillis() - startTime
        IndexStats(
            totalVectors = embeddings.size,
            indexType = if (faissEnabled) "IVF${NLIST}PQ${M}x${NBITS}" else "FALLBACK_FLAT",
            buildTimeMs = buildTime,
            sizeBytes = getIndexSize()
        )
    }

    suspend fun rebuildFromDatabase(chunkDao: ChunkDao): IndexStats = withContext(Dispatchers.IO) {
        val embeddings = mutableListOf<Pair<Long, FloatArray>>()
        var offset = 0
        val pageSize = 1000

        while (true) {
            val page = chunkDao.getEmbeddingsPage(pageSize, offset)
            if (page.isEmpty()) break
            page.forEach { embeddings.add(it.id to it.embedding) }
            offset += pageSize
        }

        buildIndex(embeddings)
    }

    suspend fun loadIndex(): Boolean = withContext(Dispatchers.IO) {
        val metadataFile = File(context.filesDir, METADATA_FILE)
        if (!metadataFile.exists()) return@withContext false

        return@withContext try {
            DataInputStream(BufferedInputStream(metadataFile.inputStream())).use { input ->
                val version = input.readInt()
                if (version != INDEX_VERSION) return@withContext false

                idMapping.clear()
                vectorFallback.clear()
                val total = input.readInt()
                repeat(total) {
                    val faissId = input.readLong()
                    val chunkId = input.readLong()
                    val dim = input.readInt()
                    val vector = FloatArray(dim)
                    for (i in 0 until dim) vector[i] = input.readFloat()
                    idMapping[faissId] = chunkId
                    vectorFallback.add(chunkId to vector)
                }
            }

            val indexFile = File(context.filesDir, INDEX_FILE)
            faissEnabled = indexFile.exists() && tryLoadFaiss(indexFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load index", e)
            false
        }
    }

    suspend fun search(queryEmbedding: FloatArray, topK: Int = 50): List<SearchResult> = withContext(Dispatchers.Default) {
        if (topK <= 0) return@withContext emptyList()

        if (faissEnabled) {
            val faissResults = trySearchFaiss(queryEmbedding, topK)
            if (faissResults.isNotEmpty()) return@withContext faissResults
        }

        // Fallback: in-memory brute force over vectors loaded from metadata/database rebuild.
        vectorFallback
            .asSequence()
            .map { (chunkId, vector) ->
                val score = cosineSimilarity(queryEmbedding, vector)
                SearchResult(chunkId, score)
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    suspend fun addVectors(newEmbeddings: List<Pair<Long, FloatArray>>) = withContext(Dispatchers.IO) {
        if (newEmbeddings.isEmpty()) return@withContext

        vectorFallback.addAll(newEmbeddings)

        if (faissEnabled) {
            tryAddFaiss(newEmbeddings)
        }

        saveIndex()
    }

    fun clearIndex() {
        faissIndex = null
        faissEnabled = false
        idMapping.clear()
        vectorFallback.clear()
        File(context.filesDir, INDEX_FILE).delete()
        File(context.filesDir, METADATA_FILE).delete()
        Log.d(TAG, "Index cleared")
    }

    private fun getIndexSize(): Long {
        val indexSize = File(context.filesDir, INDEX_FILE).takeIf { it.exists() }?.length() ?: 0L
        val metadataSize = File(context.filesDir, METADATA_FILE).takeIf { it.exists() }?.length() ?: 0L
        return indexSize + metadataSize
    }

    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        if (faissEnabled) {
            trySaveFaiss(File(context.filesDir, INDEX_FILE))
        }

        persistFallback(vectorFallback)
    }

    private fun persistFallback(embeddings: List<Pair<Long, FloatArray>>) {
        val metadataFile = File(context.filesDir, METADATA_FILE)
        DataOutputStream(BufferedOutputStream(metadataFile.outputStream())).use { out ->
            out.writeInt(INDEX_VERSION)
            out.writeInt(embeddings.size)
            embeddings.forEachIndexed { idx, (chunkId, vector) ->
                out.writeLong(idx.toLong())
                out.writeLong(chunkId)
                out.writeInt(vector.size)
                vector.forEach { out.writeFloat(it) }
            }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return (dot / (kotlin.math.sqrt(normA.toDouble()) * kotlin.math.sqrt(normB.toDouble()))).toFloat()
    }

    // -------- Reflection-based FAISS integration to avoid hard compile dependency drift --------

    private fun tryBuildFaiss(embeddings: List<Pair<Long, FloatArray>>): Boolean {
        return try {
            val indexFlatClass = Class.forName("com.facebook.faiss.IndexFlatL2")
            val indexIvfClass = Class.forName("com.facebook.faiss.IndexIVFPQ")
            val metricClass = Class.forName("com.facebook.faiss.MetricType")

            val quantizer = indexFlatClass.getConstructor(Int::class.javaPrimitiveType).newInstance(EMBEDDING_DIM)
            val metricL2 = metricClass.getField("METRIC_L2").get(null)

            val ivf = if (embeddings.size < 1000) {
                quantizer
            } else {
                indexIvfClass.getConstructor(
                    Class.forName("com.facebook.faiss.Index"),
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    metricClass
                ).newInstance(quantizer, EMBEDDING_DIM, NLIST, M, NBITS, metricL2)
            }

            val matrix = flattenVectors(embeddings.map { it.second }.toTypedArray())

            if (ivf.javaClass.name.contains("IndexIVFPQ")) {
                val trainingSize = minOf(10000, embeddings.size)
                val training = flattenVectors(embeddings.take(trainingSize).map { it.second }.toTypedArray())
                ivf.javaClass.getMethod("train", Long::class.javaPrimitiveType, FloatArray::class.java)
                    .invoke(ivf, trainingSize.toLong(), training)
                ivf.javaClass.getField("nprobe").setInt(ivf, 10)
            }

            ivf.javaClass.getMethod("add", Long::class.javaPrimitiveType, FloatArray::class.java)
                .invoke(ivf, embeddings.size.toLong(), matrix)

            idMapping.clear()
            embeddings.forEachIndexed { idx, (chunkId, _) -> idMapping[idx.toLong()] = chunkId }
            faissIndex = ivf
            true
        } catch (e: Throwable) {
            Log.w(TAG, "FAISS build failed, fallback enabled", e)
            false
        }
    }

    private fun trySearchFaiss(queryEmbedding: FloatArray, topK: Int): List<SearchResult> {
        return try {
            val current = faissIndex ?: return emptyList()
            val distances = FloatArray(topK)
            val labels = LongArray(topK)

            current.javaClass.getMethod(
                "search",
                Long::class.javaPrimitiveType,
                FloatArray::class.java,
                Long::class.javaPrimitiveType,
                FloatArray::class.java,
                LongArray::class.java
            ).invoke(current, 1L, queryEmbedding, topK.toLong(), distances, labels)

            buildList {
                for (i in 0 until topK) {
                    val faissId = labels[i]
                    if (faissId < 0) continue
                    val chunkId = idMapping[faissId] ?: continue
                    val distance = distances[i]
                    val similarity = 1.0f - (distance * distance / 2.0f)
                    add(SearchResult(chunkId, similarity))
                }
            }.sortedByDescending { it.score }
        } catch (e: Throwable) {
            Log.w(TAG, "FAISS search failed, fallback enabled", e)
            emptyList()
        }
    }

    private fun tryAddFaiss(newEmbeddings: List<Pair<Long, FloatArray>>) {
        try {
            val current = faissIndex ?: return
            val matrix = flattenVectors(newEmbeddings.map { it.second }.toTypedArray())
            val ntotal = current.javaClass.getMethod("ntotal").invoke(current) as Long
            current.javaClass.getMethod("add", Long::class.javaPrimitiveType, FloatArray::class.java)
                .invoke(current, newEmbeddings.size.toLong(), matrix)

            newEmbeddings.forEachIndexed { idx, (chunkId, _) ->
                idMapping[ntotal + idx] = chunkId
            }
        } catch (e: Throwable) {
            Log.w(TAG, "FAISS incremental add failed", e)
        }
    }

    private fun trySaveFaiss(indexFile: File) {
        try {
            val indexClass = Class.forName("com.facebook.faiss.Index")
            indexClass.getMethod("write_index", indexClass, String::class.java)
                .invoke(null, faissIndex, indexFile.absolutePath)
        } catch (e: Throwable) {
            Log.w(TAG, "FAISS save failed", e)
        }
    }

    private fun tryLoadFaiss(indexFile: File): Boolean {
        return try {
            val indexClass = Class.forName("com.facebook.faiss.Index")
            faissIndex = indexClass.getMethod("read_index", String::class.java)
                .invoke(null, indexFile.absolutePath)
            faissIndex != null
        } catch (e: Throwable) {
            Log.w(TAG, "FAISS load failed", e)
            false
        }
    }

    private fun flattenVectors(vectors: Array<FloatArray>): FloatArray {
        val flattened = FloatArray(vectors.size * EMBEDDING_DIM)
        vectors.forEachIndexed { idx, vector ->
            val source = if (vector.size == EMBEDDING_DIM) vector else vector.copyOf(EMBEDDING_DIM)
            source.copyInto(flattened, destinationOffset = idx * EMBEDDING_DIM)
        }
        return flattened
    }
}

