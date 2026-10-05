package com.augt.localseek.diagnostics

/**
 * Debug-only helper for the benchmark stall watchdog (see docs/investigations/BENCH_HANG.md). The harness calls [touch] whenever a
 * benchmark step completes; [isStalled] turns true once nothing completed for [timeoutMs]. [frames] renders thread stacks as
 * class and method names only, so a dump never carries arguments, query text or file names.
 */
class StallDetector(private val timeoutMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var lastProgress = clock()

    /** Last phase the harness announced ("gc-begin <arm>", "arm-run <arm>", "export"); logged first when a stall fires. */
    @Volatile var phase: String = "init"

    fun touch() { lastProgress = clock() }

    fun secondsSinceProgress(): Long = (clock() - lastProgress) / 1000

    fun isStalled(): Boolean = clock() - lastProgress >= timeoutMs

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

        fun frames(stacks: Map<Thread, Array<StackTraceElement>>): List<String> = stacks.entries
            .sortedBy { it.key.name }
            .flatMap { (thread, stack) ->
                listOf("THREAD ${thread.name} state=${thread.state}") + stack.map { "  at ${it.className}.${it.methodName}" }
            }
    }
}

/**
 * Runs explicit GCs on a daemon thread and waits at most [timeoutMs] for each, because a stalled ART GC would otherwise hang the test
 * thread for hours (BENCH_HANG.md). After one timeout [stalled] stays true and later requests are skipped without calling [gc].
 */
class GcGuard(private val timeoutMs: Long = 20_000L, private val gc: () -> Unit = { System.gc() }, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Outcome { DONE, TIMEOUT, SKIPPED }

    @Volatile var stalled = false; private set
    var calls = 0; private set
    var maxMs = 0L; private set
    var timeouts = 0; private set

    fun request(): Outcome {
        if (stalled) return Outcome.SKIPPED
        calls++
        val done = java.util.concurrent.CountDownLatch(1)
        val start = clock()
        Thread { try { gc() } finally { done.countDown() } }.apply { isDaemon = true; name = "bench-gc"; start() }
        val finished = done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        maxMs = maxOf(maxMs, clock() - start)
        if (!finished) { stalled = true; timeouts++; return Outcome.TIMEOUT }
        return Outcome.DONE
    }
}
