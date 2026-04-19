package com.augt.localseek.ui

import android.util.LruCache
import com.augt.localseek.retrieval.FileResult

class QueryCache(maxSize: Int = 50) {
    private val cache = LruCache<String, List<FileResult>>(maxSize)

    fun get(query: String): List<FileResult>? = cache.get(normalize(query))

    fun put(query: String, results: List<FileResult>) {
        cache.put(normalize(query), results)
    }

    private fun normalize(query: String): String = query.lowercase().trim()
}

