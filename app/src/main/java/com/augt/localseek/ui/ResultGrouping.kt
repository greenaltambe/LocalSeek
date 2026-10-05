package com.augt.localseek.ui

import androidx.annotation.StringRes
import com.augt.localseek.R
import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult

/** One titled block of results in the list. */
data class ResultGroup(val type: EntityType, val results: List<FileResult>) {
    @get:StringRes
    val titleRes: Int
        get() = when (type) {
            EntityType.APP -> R.string.group_apps
            EntityType.CONTACT -> R.string.group_contacts
            EntityType.FILE -> R.string.group_files
            EntityType.IMAGE -> R.string.group_images
        }
}

/**
 * Groups a ranked result list by entity type for display (Apps, Contacts, Files, Images).
 * Order inside a group is the original rank; groups are ordered by the rank of their best result,
 * so the group holding the top hit comes first. Pure display logic: scores and ranking are untouched.
 */
fun groupResultsByType(results: List<FileResult>): List<ResultGroup> {
    val firstIndex = LinkedHashMap<EntityType, Int>()
    results.forEachIndexed { i, r -> firstIndex.putIfAbsent(r.entityType, i) }
    return results.groupBy { it.entityType }
        .map { (type, items) -> ResultGroup(type, items) }
        .sortedBy { firstIndex.getValue(it.type) }
}

/** One row of the flattened results list. [key] is stable across recompositions for LazyColumn. */
sealed interface ListRow {
    val key: String

    data class Header(val group: ResultGroup) : ListRow {
        override val key get() = "header_${group.type.name}"
    }

    data class Item(val type: EntityType, val result: FileResult) : ListRow {
        override val key get() = "${type.name}:${result.filePath}"
    }

    data object WebFallback : ListRow {
        override val key get() = "web_fallback"
    }
}

/**
 * Flattens groups into list rows in index order. In the normal list a header comes before its items. In the
 * one-handed (reversed) list index 0 sits next to the search bar, so the best result stays closest to it and each
 * header comes after its items, which puts it visually above the group. The web fallback is always the last row.
 */
fun buildListRows(groups: List<ResultGroup>, reversed: Boolean, includeWebFallback: Boolean): List<ListRow> {
    val rows = mutableListOf<ListRow>()
    groups.forEach { group ->
        val items = group.results.map { ListRow.Item(group.type, it) }
        if (reversed) {
            rows += items
            rows += ListRow.Header(group)
        } else {
            rows += ListRow.Header(group)
            rows += items
        }
    }
    if (includeWebFallback) rows += ListRow.WebFallback
    return rows
}
