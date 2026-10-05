package com.augt.localseek.ui.about

/**
 * Counts quick taps on the version row. [TAPS_NEEDED] taps, each within [WINDOW_MS] of the previous one, unlock the
 * Developer options. Pure logic so it can be unit tested without Android.
 */
class DeveloperUnlock(private val tapsNeeded: Int = TAPS_NEEDED, private val windowMs: Long = WINDOW_MS) {
    private var count = 0
    private var lastTap = Long.MIN_VALUE

    /** Returns true exactly when this tap completes the sequence; the counter then starts over. */
    fun onTap(nowMillis: Long): Boolean {
        count = if (lastTap != Long.MIN_VALUE && nowMillis - lastTap <= windowMs) count + 1 else 1
        lastTap = nowMillis
        if (count >= tapsNeeded) {
            count = 0
            lastTap = Long.MIN_VALUE
            return true
        }
        return false
    }

    /** How many more taps are needed (for an optional "N taps to go" hint). */
    val remaining: Int get() = tapsNeeded - count

    companion object {
        const val TAPS_NEEDED = 7
        const val WINDOW_MS = 2_000L
    }
}
