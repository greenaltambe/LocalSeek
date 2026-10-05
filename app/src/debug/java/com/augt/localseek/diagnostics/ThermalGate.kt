package com.augt.localseek.diagnostics

/** Pure wait/timeout logic of the benchmark thermal gate: wait until the thermal status is at most [maxStatus] (LIGHT = 1), polling, with a cap. */
object ThermalGate {
    const val LIGHT = 1
    const val POLL_MS = 10_000L
    const val MAX_WAIT_MS = 20 * 60_000L

    data class Result(val waitedMs: Long, val status: Int, val timedOut: Boolean)

    fun await(
        readStatus: () -> Int,
        now: () -> Long,
        sleep: (Long) -> Unit,
        maxStatus: Int = LIGHT,
        pollMs: Long = POLL_MS,
        maxWaitMs: Long = MAX_WAIT_MS,
    ): Result {
        val start = now()
        var status = readStatus()
        while (status > maxStatus) {
            if (now() - start >= maxWaitMs) return Result(now() - start, status, true)
            sleep(pollMs)
            status = readStatus()
        }
        return Result(now() - start, status, false)
    }
}
