package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultListRowsTest {

    private fun r(id: Long, type: EntityType) = FileResult(
        id = id, filePath = "/p/$id", title = "t$id", fileType = "", bestScore = 1.0,
        snippets = emptyList(), modifiedAt = 0, sizeBytes = 0, entityType = type
    )

    private val groups = groupResultsByType(listOf(r(1, EntityType.CONTACT), r(2, EntityType.CONTACT), r(3, EntityType.FILE)))

    private fun describe(rows: List<ListRow>) = rows.map {
        when (it) {
            is ListRow.Header -> "H:${it.group.type.name}:${it.group.results.size}"
            is ListRow.Item -> "I:${it.result.id}"
            ListRow.WebFallback -> "W"
        }
    }

    @Test
    fun `normal list has header before its items and the web fallback last`() {
        assertEquals(
            listOf("H:CONTACT:2", "I:1", "I:2", "H:FILE:1", "I:3", "W"),
            describe(buildListRows(groups, reversed = false, includeWebFallback = true))
        )
    }

    @Test
    fun `reversed list keeps the best result at index 0 and puts headers after their items`() {
        val rows = buildListRows(groups, reversed = true, includeWebFallback = false)
        assertEquals(listOf("I:1", "I:2", "H:CONTACT:2", "I:3", "H:FILE:1"), describe(rows))
    }

    @Test
    fun `row keys are unique`() {
        val rows = buildListRows(groups, reversed = false, includeWebFallback = true)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows.isNotEmpty())
    }
}
