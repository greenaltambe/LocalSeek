package com.augt.localseek.diagnostics

/** Pure rules for the benchmark environment (docs/investigations/BENCH_HANG.md). Airplane mode was the trigger of the OEM freezer hang. */
object BenchEnvPolicy {
    const val FREEZER_NOTE = "OEM app freezer (OnePlus Hans) froze the benchmark process in attempts 1-5; airplane mode on was the known trigger"

    /** Null when the environment is acceptable, otherwise the refusal message. A missing reading is treated as unsafe. */
    fun refusal(airplaneModeOn: Int?): String? = when (airplaneModeOn) {
        0 -> null
        null -> "BENCH_ENV: airplane_mode_on could not be read; refusing to start the benchmark"
        else -> "BENCH_ENV: airplane_mode_on=$airplaneModeOn; switch airplane mode off (the OEM freezer hangs the run); refusing to start the benchmark"
    }

    /** A build from a dirty tree is not reproducible evidence. */
    fun dirtyRefusal(gitSha: String): String? =
        if (gitSha.endsWith("-dirty")) "BENCH_ENV: the installed app was built from a dirty git tree ($gitSha); commit, rebuild and reinstall before benchmarking" else null
}
