package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultGroupingTest {

    private fun r(id: Long, type: EntityType) =
        FileResult(id, "/p/$id", "t$id", "txt", 1.0 - id / 100.0, emptyList(), 0L, 0L, type, "k$id")

    @Test
    fun emptyInputGivesNoGroups() {
        assertTrue(groupResultsByType(emptyList()).isEmpty())
    }

    @Test
    fun groupsAreOrderedByBestRankedMember() {
        val input = listOf(
            r(1, EntityType.FILE), r(2, EntityType.APP), r(3, EntityType.FILE),
            r(4, EntityType.CONTACT), r(5, EntityType.APP)
        )
        val groups = groupResultsByType(input)
        assertEquals(listOf(EntityType.FILE, EntityType.APP, EntityType.CONTACT), groups.map { it.type })
    }

    @Test
    fun rankOrderIsPreservedInsideAGroup() {
        val input = listOf(r(1, EntityType.FILE), r(2, EntityType.APP), r(3, EntityType.FILE), r(4, EntityType.FILE))
        val files = groupResultsByType(input).first { it.type == EntityType.FILE }
        assertEquals(listOf(1L, 3L, 4L), files.results.map { it.id })
    }

    @Test
    fun noResultIsLostOrDuplicated() {
        val input = EntityType.values().flatMapIndexed { i, t -> listOf(r(i * 2L + 1, t), r(i * 2L + 2, t)) }
        val flat = groupResultsByType(input).flatMap { it.results }
        assertEquals(input.map { it.id }.sorted(), flat.map { it.id }.sorted())
    }
}
