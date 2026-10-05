package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import com.augt.localseek.retrieval.FileResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ResultFiltersTest {

    private val utc = ZoneId.of("UTC")
    private val now = LocalDate.of(2026, 10, 3).atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
    private val day = 24L * 3600 * 1000

    private fun r(id: Long, type: EntityType, fileType: String = "", modified: Long = 0L) = FileResult(
        id = id, filePath = "/p/$id", title = "t$id", fileType = fileType, bestScore = 1.0 - id / 100.0,
        snippets = emptyList(), modifiedAt = modified, sizeBytes = 1, entityType = type, stableKey = "k$id"
    )

    private val sample = listOf(
        r(1, EntityType.CONTACT),
        r(2, EntityType.FILE, "pdf", now - 1 * day),
        r(3, EntityType.APP),
        r(4, EntityType.FILE, "md", now - 20 * day),
        r(5, EntityType.IMAGE, "jpg", (now - 2 * day) / 1000), // image rows store seconds
        r(6, EntityType.FILE, "kt", now - 400 * day)
    )

    private fun ids(f: ResultFilters) = ResultFilterEngine.apply(sample, f, now, utc).map { it.id }

    @Test
    fun `default filters keep everything in order`() {
        assertEquals(listOf(1L, 2, 3, 4, 5, 6), ids(ResultFilters()))
    }

    @Test
    fun `type scope chips`() {
        assertEquals(listOf(2L, 4, 6), ids(ResultFilters(scope = TypeScope.FILES)))
        assertEquals(listOf(3L), ids(ResultFilters(scope = TypeScope.APPS)))
        assertEquals(listOf(1L), ids(ResultFilters(scope = TypeScope.CONTACTS)))
        assertEquals(listOf(5L), ids(ResultFilters(scope = TypeScope.IMAGES)))
        assertTrue(ids(ResultFilters(scope = TypeScope.DEVICE_SETTINGS)).isEmpty())
    }

    @Test
    fun `file category filter is multi select and only matches files`() {
        assertEquals(listOf(2L), ids(ResultFilters(categories = setOf(FileCategory.PDF))))
        assertEquals(listOf(2L, 4), ids(ResultFilters(categories = setOf(FileCategory.PDF, FileCategory.MARKDOWN))))
        assertEquals(listOf(6L), ids(ResultFilters(categories = setOf(FileCategory.CODE))))
    }

    @Test
    fun `date presets apply to files and images and drop undated entities`() {
        assertEquals(listOf(2L, 5), ids(ResultFilters(datePreset = DatePreset.DAYS_7)))
        assertEquals(listOf(2L, 4, 5), ids(ResultFilters(datePreset = DatePreset.DAYS_30)))
        assertEquals(listOf(2L, 4, 5), ids(ResultFilters(datePreset = DatePreset.THIS_YEAR)))
        assertEquals(listOf(2L, 5), ids(ResultFilters(datePreset = DatePreset.DAYS_7, scope = TypeScope.ALL)))
    }

    @Test
    fun `today preset starts at local midnight`() {
        val earlyToday = LocalDate.of(2026, 10, 3).atTime(1, 0).atZone(utc).toInstant().toEpochMilli()
        val list = listOf(r(7, EntityType.FILE, "txt", earlyToday), r(8, EntityType.FILE, "txt", now - day))
        val out = ResultFilterEngine.apply(list, ResultFilters(datePreset = DatePreset.TODAY), now, utc)
        assertEquals(listOf(7L), out.map { it.id })
    }

    @Test
    fun `custom range is inclusive of the end day`() {
        val start = now - 21 * day
        val end = now - 19 * day
        val f = ResultFilters(datePreset = DatePreset.CUSTOM, customStart = start, customEnd = end)
        assertEquals(listOf(4L), ids(f))
    }

    @Test
    fun `newest sort puts dated results first and keeps undated order`() {
        val out = ids(ResultFilters(sort = SortMode.NEWEST))
        assertEquals(listOf(2L, 5, 4, 6, 1, 3), out)
    }

    @Test
    fun `active count ignores the scope chip`() {
        assertEquals(0, ResultFilters(scope = TypeScope.APPS).activeCount)
        val f = ResultFilters(categories = setOf(FileCategory.PDF), datePreset = DatePreset.DAYS_7, sort = SortMode.NEWEST)
        assertEquals(3, f.activeCount)
        assertEquals(0, f.cleared().activeCount)
        assertEquals(TypeScope.ALL, f.cleared().scope)
    }
}
