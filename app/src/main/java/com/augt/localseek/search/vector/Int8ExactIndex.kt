package com.augt.localseek.search.vector

/**
 * Exact (brute-force) top-k over int8-quantised vectors. Not used by the shipped configuration; used by the scaling study.
 *
 * Symmetric per-vector quantisation: scale = max|x| / 127, q = round(x / scale), so every vector uses the full int8 range.
 * The score is the dot product computed in Int arithmetic and rescaled by both scales, so for L2-normalised vectors it
 * approximates cosine similarity. A zero vector has scale 0 and scores 0 against everything.
 * Tie rule: higher score first, then lower id (same as [ExactMemoryVectorIndex]). Single-threaded, not thread-safe to build.
 */
class Int8ExactIndex(val dim: Int) {
    private var ids = LongArray(0)
    private var codes = ByteArray(0)
    private var scales = FloatArray(0)

    val size: Int get() = ids.size

    /** Bytes held by the structure (codes + scales + ids). */
    val memoryBytes: Long get() = codes.size.toLong() + scales.size * 4L + ids.size * 8L

    /** [vectors] is row-major, `ids.size * dim` floats. */
    fun build(ids: LongArray, vectors: FloatArray) {
        require(vectors.size == ids.size * dim) { "vectors must hold ids.size * dim floats" }
        buildStreaming(ids) { row, dst -> System.arraycopy(vectors, row * dim, dst, 0, dim) }
    }

    /**
     * Builds without holding the float vectors: [readRow] fills `dst` (dim floats) with row `row`. Used when the float
     * vectors live off-heap (a mapped file) and would not fit in the heap.
     */
    fun buildStreaming(ids: LongArray, readRow: (row: Int, dst: FloatArray) -> Unit) {
        this.ids = ids.copyOf()
        codes = ByteArray(ids.size * dim)
        scales = FloatArray(ids.size)
        val tmp = FloatArray(dim)
        for (row in ids.indices) {
            readRow(row, tmp)
            scales[row] = quantiseInto(tmp, 0, codes, row * dim)
        }
    }

    fun search(query: FloatArray, k: Int): List<ScoredResult> {
        require(query.size == dim) { "query dimension ${query.size} != $dim" }
        if (k <= 0 || ids.isEmpty()) return emptyList()
        val qCodes = ByteArray(dim)
        val qScale = quantiseInto(query, 0, qCodes, 0)
        val heap = TopK(k)
        for (row in ids.indices) {
            val base = row * dim
            var dot = 0
            for (i in 0 until dim) dot += qCodes[i] * codes[base + i]
            heap.offer(ids[row], dot * qScale * scales[row])
        }
        return heap.sorted()
    }

    /** Writes the int8 codes of `src[srcOff until srcOff + dim]` to [dst] and returns the scale (0 for an all-zero vector). */
    private fun quantiseInto(src: FloatArray, srcOff: Int, dst: ByteArray, dstOff: Int): Float {
        var maxAbs = 0f
        for (i in 0 until dim) maxAbs = maxOf(maxAbs, Math.abs(src[srcOff + i]))
        if (maxAbs == 0f) return 0f
        val scale = maxAbs / 127f
        for (i in 0 until dim) dst[dstOff + i] = Math.round(src[srcOff + i] / scale).coerceIn(-127, 127).toByte()
        return scale
    }
}

/** Bounded top-k by (score desc, id asc). */
internal class TopK(private val k: Int) {
    private val heap = java.util.PriorityQueue<ScoredResult>(k.coerceIn(1, 1024)) { a, b ->
        if (a.score != b.score) a.score.compareTo(b.score) else b.id.compareTo(a.id)
    }

    fun offer(id: Long, score: Float) {
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

    fun sorted(): List<ScoredResult> =
        heap.toList().sortedWith(compareByDescending<ScoredResult> { it.score }.thenBy { it.id })
}
