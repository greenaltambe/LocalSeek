package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue

/**
 * Exact top-k search over an in-memory, contiguous copy of the chunk embeddings.
 *
 * - Same score as the database path ([BruteForceVectorIndex]): cosine computed with the identical float accumulation order, so
 *   scores are bit-identical; vectors are stored as is plus their precomputed norm.
 * - Tie rule (total order): higher score first, then lower chunk id. (The database path keeps the first-seen, i.e. lowest id,
 *   among equal scores at the k-th boundary; its heap is not specified among equal scores, this one is.)
 * - Rows whose embedding is not [dim] floats long (failed or truncated embeddings) are skipped, never scored and never thrown on.
 * - Size guard: if the estimated size exceeds [maxBytes] the index is not built and [fallback] (the database path) answers.
 * - Invalidation: the structure is rebuilt when [versionProvider] returns a value different from the one it was built for
 *   (callers combine the LSH index generation and the chunk count) or after [invalidate].
 * - Concurrency: readers use an immutable snapshot published through a volatile reference; building is single-flight
 *   behind a [Mutex] (same pattern as the encoder's interpreter mutex).
 */
class ExactMemoryVectorIndex(
    private val chunkDao: ChunkDao,
    private val fallback: VectorIndex = BruteForceVectorIndex(chunkDao),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val versionProvider: suspend () -> Long = { 0L },
    private val debugLog: (String) -> Unit = {}
) : VectorIndex {

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 96L * 1024 * 1024
        const val DIM = 384
        private const val PAGE_SIZE = 500
        private const val BYTES_PER_ROW = DIM * 4L + 8L + 8L   // floats + id + norm
    }

    override val backendName: String = "exact_memory"

    private class Snapshot(
        val version: Long,
        val size: Int,
        val ids: LongArray,
        val data: FloatArray,
        val sqrtNorms: DoubleArray
    )

    private val buildMutex = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var tooLargeForVersion: Long? = null

    /** Number of times the structure was (re)built; for tests and diagnostics. */
    @Volatile var buildCount: Int = 0
        private set

    /** Rows held by the current snapshot, or -1 when none is built. */
    val rowCount: Int get() = snapshot?.size ?: -1

    fun invalidate() {
        snapshot = null
        tooLargeForVersion = null
    }

    override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
        if (k <= 0 || queryVec.size != DIM) return emptyList()
        val version = versionProvider()
        if (tooLargeForVersion == version) return fallback.search(queryVec, k)
        val snap = snapshot?.takeIf { it.version == version } ?: build(version) ?: return fallback.search(queryVec, k)
        return scan(snap, queryVec, k)
    }

    override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {
        invalidate()
    }

    private suspend fun build(version: Long): Snapshot? = buildMutex.withLock {
        snapshot?.takeIf { it.version == version }?.let { return it }
        val upperBound = chunkDao.countAllChunks()
        if (upperBound.toLong() * BYTES_PER_ROW > maxBytes) {
            debugLog("exact_memory: ${upperBound.toLong() * BYTES_PER_ROW} bytes needed > guard $maxBytes; using database path")
            tooLargeForVersion = version
            snapshot = null
            return null
        }
        tooLargeForVersion = null
        val ids = LongArray(upperBound)
        val data = FloatArray(upperBound * DIM)
        val norms = DoubleArray(upperBound)
        var n = 0
        var lastId = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = chunkDao.getEmbeddingsPage(PAGE_SIZE, lastId)
            if (page.isEmpty()) break
            for (row in page) {
                lastId = row.id
                if (row.embedding.size != DIM) continue
                if (n >= upperBound) break   // rows added while loading; the next version check rebuilds
                var normSq = 0f
                val base = n * DIM
                for (i in 0 until DIM) {
                    val x = row.embedding[i]
                    data[base + i] = x
                    normSq += x * x
                }
                ids[n] = row.id
                norms[n] = Math.sqrt(normSq.toDouble())
                n++
            }
        }
        buildCount++
        debugLog("exact_memory: built rows=$n")
        Snapshot(version, n, ids, data, norms).also { snapshot = it }
    }

    private fun scan(snap: Snapshot, q: FloatArray, k: Int): List<ScoredResult> {
        var qNormSq = 0f
        for (x in q) qNormSq += x * x
        val qNorm = Math.sqrt(qNormSq.toDouble())
        // min-heap on the total order, so the head is the current worst of the kept k
        val heap = PriorityQueue<ScoredResult>(k.coerceAtMost(1024).coerceAtLeast(1)) { a, b ->
            if (a.score != b.score) a.score.compareTo(b.score) else b.id.compareTo(a.id)
        }
        for (row in 0 until snap.size) {
            val base = row * DIM
            var dot = 0f
            for (i in 0 until DIM) dot += q[i] * snap.data[base + i]
            val denom = qNorm * snap.sqrtNorms[row]
            // same expression as VectorUtils.cosineSimilarity, including 0 for a zero vector
            val score = if (qNormSq == 0f || snap.sqrtNorms[row] == 0.0) 0f else (dot / denom).toFloat()
            val id = snap.ids[row]
            if (heap.size < k) {
                heap.add(ScoredResult(id, score))
            } else {
                val worst = heap.peek()
                if (score > worst.score || (score == worst.score && id < worst.id)) {
                    heap.poll()
                    heap.add(ScoredResult(id, score))
                }
            }
        }
        return heap.toList().sortedWith(compareByDescending<ScoredResult> { it.score }.thenBy { it.id })
    }
}
