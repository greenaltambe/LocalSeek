package com.augt.localseek.search.vector

/**
 * Two-stage search: sign bits packed into a LongArray give a cheap Hamming shortlist of [rescoreCandidates] (k'), which is
 * then re-scored with the original float32 vectors. Not used by the shipped configuration; used by the scaling study.
 *
 * Bit i of a vector is 1 when its component i is > 0. The float vectors are kept (a reference, not a copy) for rescoring,
 * so [memoryBytes] counts the packed bits and ids only; add the float vectors to get the total footprint.
 * Rescoring score is the plain dot product (cosine for L2-normalised vectors). Tie rule: higher score, then lower id; the
 * shortlist tie rule is smaller Hamming distance, then lower row.
 */
class BinaryRescoreIndex(val dim: Int, val rescoreCandidates: Int = 100) {
    private val words = (dim + 63) / 64
    private var ids = LongArray(0)
    private var bits = LongArray(0)
    private var floats = FloatArray(0)
    private var readRow: (row: Int, dst: FloatArray) -> Unit = { _, _ -> }

    val size: Int get() = ids.size

    /** Bytes of the binary structure (bits + ids), excluding the float vectors kept for rescoring. */
    val memoryBytes: Long get() = bits.size * 8L + ids.size * 8L

    /** [vectors] is row-major, `ids.size * dim` floats; it is referenced, not copied. */
    fun build(ids: LongArray, vectors: FloatArray) {
        require(vectors.size == ids.size * dim) { "vectors must hold ids.size * dim floats" }
        floats = vectors
        buildStreaming(ids) { row, dst -> System.arraycopy(floats, row * dim, dst, 0, dim) }
    }

    /**
     * Builds the sign bits without holding the float vectors; [readRow] (kept for rescoring) fills `dst` with row `row`.
     * Use when the float vectors live off-heap (a mapped file).
     */
    fun buildStreaming(ids: LongArray, readRow: (row: Int, dst: FloatArray) -> Unit) {
        this.ids = ids.copyOf()
        this.readRow = readRow
        bits = LongArray(ids.size * words)
        val tmp = FloatArray(dim)
        for (row in ids.indices) {
            readRow(row, tmp)
            pack(tmp, 0, bits, row * words)
        }
    }

    fun search(query: FloatArray, k: Int, kPrime: Int = rescoreCandidates): List<ScoredResult> {
        require(query.size == dim) { "query dimension ${query.size} != $dim" }
        if (k <= 0 || ids.isEmpty()) return emptyList()
        val shortlistSize = maxOf(kPrime, k).coerceAtMost(ids.size)
        val qBits = LongArray(words)
        pack(query, 0, qBits, 0)
        // shortlist: smallest Hamming distance; max-heap on (distance, row) so the head is the worst kept
        val heap = java.util.PriorityQueue<Long>(shortlistSize.coerceAtLeast(1), Comparator.reverseOrder())
        for (row in ids.indices) {
            var d = 0
            val base = row * words
            for (w in 0 until words) d += java.lang.Long.bitCount(bits[base + w] xor qBits[w])
            val key = (d.toLong() shl 32) or row.toLong()
            if (heap.size < shortlistSize) heap.add(key) else if (key < heap.peek()) { heap.poll(); heap.add(key) }
        }
        val top = TopK(k)
        val tmp = FloatArray(dim)
        for (key in heap) {
            val row = (key and 0xFFFFFFFFL).toInt()
            readRow(row, tmp)
            var dot = 0f
            for (i in 0 until dim) dot += query[i] * tmp[i]
            top.offer(ids[row], dot)
        }
        return top.sorted()
    }

    private fun pack(src: FloatArray, srcOff: Int, dst: LongArray, dstOff: Int) {
        for (i in 0 until dim) if (src[srcOff + i] > 0f) dst[dstOff + (i ushr 6)] = dst[dstOff + (i ushr 6)] or (1L shl (i and 63))
    }
}
