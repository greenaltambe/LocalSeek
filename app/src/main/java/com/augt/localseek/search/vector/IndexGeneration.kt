package com.augt.localseek.search.vector

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counter that changes whenever an indexing run finished, so caches keyed on the stored data (the query cache, the
 * in-memory dense index) know to refresh. It replaces the generation the LSH index used to supply; it needs no LSH index.
 */
class IndexGeneration {
    private val counter = AtomicLong(0L)

    val current: Long get() = counter.get()

    fun bump(): Long = counter.incrementAndGet()
}
