package com.augt.localseek.diagnostics

/** A pool file with no data rows must never replace a real one (the canonical test once overwrote the image pool with a header). */
object PoolGuard {
    fun check(name: String, lines: List<String>) {
        check(lines.size > 1) { "BENCH_POOL: refusing to write $name with no data rows (header only)" }
    }
}
