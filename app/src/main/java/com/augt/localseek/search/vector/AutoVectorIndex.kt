package com.augt.localseek.search.vector

import com.augt.localseek.data.ChunkDao
import com.augt.localseek.data.ChunkEmbedding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Size-adaptive dense index (shipped configuration, [com.augt.localseek.core.config.DenseIndexType.AUTO]).
 *
 * - Up to [exactMaxVectors] chunk vectors (50,000 by default): exact in-memory search, delegated unchanged to [exact]
 *   (the same index registered arm E9 uses), so rankings and scores are identical to that arm.
 * - Above that: [BinaryRescoreIndex], a Hamming shortlist of [rescoreCandidates] (k' = 200) re-scored with the float vectors.
 *   Study 2 (scaling microbenchmark, one phone): recall@10 0.975-0.977 and p95 at most 19 ms up to 200k vectors, where exact
 *   float32 search takes 66 ms (100k) to 196 ms (200k). Scores on this path are plain dot products, which equal cosine for the
 *   L2-normalised MiniLM vectors.
 * - If even the binary structure plus the float vectors would exceed [maxBytes], [fallback] (the database scan) answers.
 *
 * The size test uses the chunk row count, an upper bound on the number of vectors. The binary structure is rebuilt when
 * [versionProvider] returns a different value (callers combine the index generation and the chunk count). The index is built
 * on the first search above the threshold; [IndexWarmup] can trigger that in the background. Readers use an immutable
 * snapshot; building is single-flight behind a [Mutex].
 */
class AutoVectorIndex(
    private val chunkDao: ChunkDao,
    private val exact: VectorIndex,
    private val fallback: VectorIndex = BruteForceVectorIndex(chunkDao),
    private val exactMaxVectors: Int = DEFAULT_EXACT_MAX_VECTORS,
    private val rescoreCandidates: Int = DEFAULT_RESCORE_CANDIDATES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val versionProvider: suspend () -> Long = { 0L },
    private val debugLog: (String) -> Unit = {}
) : VectorIndex {

    enum class Path { NONE, EXACT_MEMORY, BINARY_RESCORE, DATABASE_SCAN }

    /** What the last search used, and the number of chunk rows it saw. For the performance page and tests. */
    data class Status(val path: Path, val vectorCount: Int)

    companion object {
        const val DEFAULT_EXACT_MAX_VECTORS = 50_000
        const val DEFAULT_RESCORE_CANDIDATES = 200
        const val DEFAULT_MAX_BYTES: Long = 192L * 1024 * 1024
        const val DIM = ExactMemoryVectorIndex.DIM
        private const val PAGE_SIZE = 500
        private const val BYTES_PER_ROW = DIM * 4L + 8L + (DIM / 64) * 8L   // floats + id + packed sign bits
    }

    override val backendName: String = "auto"

    private class Snapshot(val version: Long, val index: BinaryRescoreIndex)

    private val buildMutex = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var tooLargeForVersion: Long? = null
    @Volatile private var lastStatus = Status(Path.NONE, 0)

    /** Number of times the binary structure was (re)built; for tests. */
    @Volatile var binaryBuildCount: Int = 0
        private set

    fun status(): Status = lastStatus

    override suspend fun search(queryVec: FloatArray, k: Int): List<ScoredResult> {
        if (k <= 0 || queryVec.size != DIM) return emptyList()
        val count = chunkDao.countAllChunks()
        if (count <= exactMaxVectors) {
            snapshot = null   // free the binary structure if the corpus shrank below the threshold
            lastStatus = Status(Path.EXACT_MEMORY, count)
            return exact.search(queryVec, k)
        }
        val version = versionProvider()
        if (tooLargeForVersion == version) {
            lastStatus = Status(Path.DATABASE_SCAN, count)
            return fallback.search(queryVec, k)
        }
        val snap = snapshot?.takeIf { it.version == version } ?: build(version, count)
        if (snap == null) {
            lastStatus = Status(Path.DATABASE_SCAN, count)
            return fallback.search(queryVec, k)
        }
        lastStatus = Status(Path.BINARY_RESCORE, count)
        return snap.index.search(queryVec, k)
    }

    override suspend fun buildIndex(embeddings: List<ChunkEmbedding>) {
        snapshot = null
        tooLargeForVersion = null
    }

    private suspend fun build(version: Long, upperBound: Int): Snapshot? = buildMutex.withLock {
        snapshot?.takeIf { it.version == version }?.let { return it }
        if (upperBound.toLong() * BYTES_PER_ROW > maxBytes) {
            debugLog("auto: ${upperBound.toLong() * BYTES_PER_ROW} bytes needed > guard $maxBytes; using database path")
            tooLargeForVersion = version
            snapshot = null
            return null
        }
        tooLargeForVersion = null
        var ids = LongArray(upperBound)
        var data = FloatArray(upperBound * DIM)
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
                System.arraycopy(row.embedding, 0, data, n * DIM, DIM)
                ids[n] = row.id
                n++
            }
        }
        if (n < upperBound) {   // rows without a usable embedding: trim to the exact size BinaryRescoreIndex requires
            ids = ids.copyOf(n)
            data = data.copyOf(n * DIM)
        }
        val index = BinaryRescoreIndex(DIM, rescoreCandidates)
        index.build(ids, data)
        binaryBuildCount++
        debugLog("auto: binary index built rows=$n k'=$rescoreCandidates")
        Snapshot(version, index).also { snapshot = it }
    }
}
