package com.augt.localseek.notify

import java.text.NumberFormat
import java.util.Locale

/** Keys of the WorkManager progress data published by the index worker while it runs. */
object IndexProgressKeys {
    const val DONE = "indexed_files"
    const val TOTAL = "total_files"
}

/**
 * "[done] of [total] files". [total] is an estimate (the number of parsable files found on the device) and can be 0
 * while it is still being counted; the index can also hold more files than currently exist, so [done] is clamped.
 */
data class IndexProgress(val done: Int, val total: Int) {

    val isDeterminate: Boolean get() = total > 0

    /** 0..1, or null while the total is unknown (indeterminate bar). */
    val fraction: Float?
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    /** Whole percent for text such as "42%", or null while unknown. */
    val percent: Int? get() = fraction?.let { (it * 100).toInt() }

    /** Done count as shown to the user: never above the total. */
    val shownDone: Int get() = if (total > 0) done.coerceIn(0, total) else done.coerceAtLeast(0)

    companion object {
        /** Reads the worker's progress data; null when either value is missing (not published yet). */
        fun fromData(done: Int, total: Int): IndexProgress? =
            if (done < 0 || total < 0) null else IndexProgress(done, total)
    }
}

/** Number formatting for the banner and the notification: locale-aware thousands separators. */
object IndexProgressText {

    fun count(n: Int, locale: Locale = Locale.getDefault()): String = NumberFormat.getIntegerInstance(locale).format(n)

    /** "4,200 of 6,000" or, when the total is unknown, just "4,200". */
    fun counts(progress: IndexProgress, locale: Locale = Locale.getDefault()): Pair<String, String?> =
        count(progress.shownDone, locale) to (if (progress.isDeterminate) count(progress.total, locale) else null)
}
