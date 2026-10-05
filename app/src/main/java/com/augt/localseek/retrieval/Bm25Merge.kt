package com.augt.localseek.retrieval

/**
 * Rank-based merge of the per-table BM25 rankings (chunks/files, apps, contacts).
 *
 * Each group is already ordered best first. A hit at 1-based rank r in its own table gets (k + 1) / (k + r), i.e. the
 * reciprocal-rank value 1 / (k + r) rescaled so the best hit of any table scores 1.0 (scores stay in (0, 1]).
 * Tie rule: equal merged scores are ordered by group index (files, then apps, then contacts), then by position, so the
 * result is deterministic. No raw FTS5 score is compared across tables.
 */
internal object Bm25Merge {
    const val RRF_K = 60

    fun <T> rrfAcrossTables(groups: List<List<T>>, limit: Int, k: Int = RRF_K): List<Pair<T, Float>> {
        data class Entry<T>(val item: T, val score: Float, val group: Int, val pos: Int)
        val all = ArrayList<Entry<T>>()
        groups.forEachIndexed { g, list ->
            list.forEachIndexed { i, item ->
                all.add(Entry(item, ((k + 1).toDouble() / (k + i + 1)).toFloat(), g, i))
            }
        }
        return all.sortedWith(compareByDescending<Entry<T>> { it.score }.thenBy { it.group }.thenBy { it.pos })
            .take(limit)
            .map { it.item to it.score }
    }
}
