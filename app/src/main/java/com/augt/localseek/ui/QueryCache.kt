package com.augt.localseek.ui

import android.util.LruCache
import com.augt.localseek.retrieval.FileResult

/**
 * In-memory LRU cache for search results.
 *
 * Config- and generation-aware: incorporates [configHash] and [indexGeneration]
 * into cache keys so different retrieval configurations and index updates
 * never cross-contaminate or return stale results.
 */
class QueryCache(private val maxSize: Int = 50) {
    private val cache = object : LinkedHashMap<String, List<FileResult>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileResult>>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(query: String, configHash: String = "", indexGeneration: Long = 0L): List<FileResult>? {
        val key = makeKey(query, configHash, indexGeneration)
        return cache[key]
    }

    @Synchronized
    fun put(query: String, results: List<FileResult>, configHash: String = "", indexGeneration: Long = 0L) {
        val key = makeKey(query, configHash, indexGeneration)
        cache[key] = results
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    private fun makeKey(query: String, configHash: String, indexGeneration: Long): String {
        val normalized = query.lowercase().trim()
        return "$normalized::$configHash::$indexGeneration"
    }
}
