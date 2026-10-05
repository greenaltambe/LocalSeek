package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which kinds of results the chip row shows. DEVICE_SETTINGS shows only the offline settings entries. */
enum class TypeScope { ALL, FILES, APPS, CONTACTS, IMAGES, DEVICE_SETTINGS }

enum class DatePreset { ANYTIME, TODAY, DAYS_7, DAYS_30, THIS_YEAR, CUSTOM }

enum class SortMode { BEST_MATCH, NEWEST }

/**
 * Client-side filters applied to the already-retrieved result list; the retrieval call is never changed.
 * [customStart] and [customEnd] are inclusive epoch-millisecond bounds used by [DatePreset.CUSTOM].
 */
data class ResultFilters(
    val scope: TypeScope = TypeScope.ALL,
    val categories: Set<FileCategory> = emptySet(),
    val datePreset: DatePreset = DatePreset.ANYTIME,
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val sort: SortMode = SortMode.BEST_MATCH
) {
    /** Number of active choices inside the Filters sheet (the scope chip is not counted). */
    val activeCount: Int
        get() = (if (categories.isNotEmpty()) 1 else 0) +
            (if (datePreset != DatePreset.ANYTIME) 1 else 0) +
            (if (sort != SortMode.BEST_MATCH) 1 else 0)

    /** Same scope, sheet filters reset. */
    fun cleared(): ResultFilters = ResultFilters(scope = scope)
}

object ResultFilterEngine {

    fun apply(
        results: List<FileResult>,
        filters: ResultFilters,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<FileResult> {
        var out = results
        out = when (filters.scope) {
            TypeScope.ALL -> out
            TypeScope.FILES -> out.filter { it.entityType == EntityType.FILE }
            TypeScope.APPS -> out.filter { it.entityType == EntityType.APP }
            TypeScope.CONTACTS -> out.filter { it.entityType == EntityType.CONTACT }
            TypeScope.IMAGES -> out.filter { it.entityType == EntityType.IMAGE }
            TypeScope.DEVICE_SETTINGS -> emptyList()
        }
        if (filters.categories.isNotEmpty()) {
            out = out.filter { it.entityType == EntityType.FILE && FileCategory.of(it.fileType) in filters.categories }
        }
        dateRange(filters, nowMillis, zone)?.let { range ->
            out = out.filter {
                ResultFormat.showsDate(it.entityType) && ResultFormat.toEpochMillis(it.modifiedAt) in range
            }
        }
        if (filters.sort == SortMode.NEWEST) {
            // Stable sort: results without a date (apps, contacts) keep their relative order at the end.
            out = out.sortedByDescending {
                if (ResultFormat.showsDate(it.entityType)) ResultFormat.toEpochMillis(it.modifiedAt) else Long.MIN_VALUE
            }
        }
        return out
    }

    /** Inclusive millisecond range for the active date choice, or null for "anytime". */
    fun dateRange(filters: ResultFilters, nowMillis: Long, zone: ZoneId): LongRange? {
        fun startOfDay(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (filters.datePreset) {
            DatePreset.ANYTIME -> null
            DatePreset.TODAY -> startOfDay(today)..Long.MAX_VALUE
            DatePreset.DAYS_7 -> (nowMillis - 7L * DAY_MS)..Long.MAX_VALUE
            DatePreset.DAYS_30 -> (nowMillis - 30L * DAY_MS)..Long.MAX_VALUE
            DatePreset.THIS_YEAR -> startOfDay(LocalDate.of(today.year, 1, 1))..Long.MAX_VALUE
            DatePreset.CUSTOM -> {
                val s = filters.customStart ?: return null
                val e = filters.customEnd ?: s
                s..(e + DAY_MS - 1)
            }
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
