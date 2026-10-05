package com.augt.localseek.search.vector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Loads a lazily built vector index (the exact in-memory index) once in the background so the first user search does not
 * pay the cold load. A dummy unit query is enough: the index builds itself on its first search.
 *
 * Meant to run from an application-level coroutine on a background dispatcher after [startDelayMs], so it does not compete
 * with app start-up. It is cancellable (cancellation propagates), and any other failure is swallowed: warming is an
 * optimisation and a failure only means the first search builds the index as before.
 */
class IndexWarmup(
    private val index: VectorIndex,
    private val dim: Int = ExactMemoryVectorIndex.DIM,
    private val startDelayMs: Long = DEFAULT_START_DELAY_MS
) {
    /** Returns true when the warm-up search ran, false when it failed. */
    suspend fun run(): Boolean {
        delay(startDelayMs)
        return try {
            val q = FloatArray(dim).also { it[0] = 1f }
            index.search(q, 1)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_START_DELAY_MS = 2_000L
    }
}
