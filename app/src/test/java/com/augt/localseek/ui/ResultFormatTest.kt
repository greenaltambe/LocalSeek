package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ResultFormatTest {

    private val utc = ZoneId.of("UTC")
    private fun millis(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDate.of(y, m, d).atTime(h, 0).atZone(utc).toInstant().toEpochMilli()

    @Test
    fun `seconds timestamps are converted to milliseconds`() {
        // the 1970-01-21 bug: a seconds value read as milliseconds
        val seconds = 1_700_000_000L
        assertEquals(1_700_000_000_000L, ResultFormat.toEpochMillis(seconds))
        assertEquals(1_700_000_000_000L, ResultFormat.toEpochMillis(1_700_000_000_000L))
        assertEquals(0L, ResultFormat.toEpochMillis(0L))
    }

    @Test
    fun `image seconds timestamp does not show 1970`() {
        val label = ResultFormat.dateLabel(1_700_000_000L, millis(2026, 10, 3), utc)
        assertTrue(label is ResultFormat.DateLabel.Absolute)
        assertEquals(2023, (label as ResultFormat.DateLabel.Absolute).date.year)
    }

    @Test
    fun `date label is relative for the last week`() {
        val now = millis(2026, 10, 3)
        assertEquals(ResultFormat.DateLabel.Today, ResultFormat.dateLabel(millis(2026, 10, 3, 1), now, utc))
        assertEquals(ResultFormat.DateLabel.Yesterday, ResultFormat.dateLabel(millis(2026, 10, 2, 23), now, utc))
        assertEquals(ResultFormat.DateLabel.DaysAgo(4), ResultFormat.dateLabel(millis(2026, 9, 29), now, utc))
        assertTrue(ResultFormat.dateLabel(millis(2026, 9, 1), now, utc) is ResultFormat.DateLabel.Absolute)
    }

    @Test
    fun `unknown timestamp has no label`() {
        assertNull(ResultFormat.dateLabel(0L, millis(2026, 10, 3), utc))
    }

    @Test
    fun `dates are shown for files and images only`() {
        assertTrue(ResultFormat.showsDate(EntityType.FILE))
        assertTrue(ResultFormat.showsDate(EntityType.IMAGE))
        assertFalse(ResultFormat.showsDate(EntityType.CONTACT))
        assertFalse(ResultFormat.showsDate(EntityType.APP))
    }

    @Test
    fun `parent folder keeps the last two folder segments`() {
        assertEquals("Documents/Notes", ResultFormat.parentFolder("/storage/emulated/0/Documents/Notes/a.txt"))
        assertEquals("Download", ResultFormat.parentFolder("/Download/a.pdf"))
        assertEquals("Documents/Work", ResultFormat.parentFolder("C:\\x\\Documents\\Work\\a.md"))
        assertNull(ResultFormat.parentFolder("a.txt"))
        assertNull(ResultFormat.parentFolder("content://media/external/images/media/12"))
    }

    @Test
    fun `identical file names in different folders get different subtitles`() {
        assertNotEquals(
            ResultFormat.parentFolder("/sdcard/Documents/A/report.pdf"),
            ResultFormat.parentFolder("/sdcard/Documents/B/report.pdf")
        )
    }

    @Test
    fun `initial and avatar hue are deterministic`() {
        assertEquals("A", ResultFormat.initial("alice"))
        assertEquals("7", ResultFormat.initial("  7up"))
        assertEquals("#", ResultFormat.initial("---"))
        assertEquals(ResultFormat.avatarHue("Alice"), ResultFormat.avatarHue("  alice "), 0f)
        assertTrue(ResultFormat.avatarHue("anything at all") in 0f..360f)
    }

    @Test
    fun `size formatting`() {
        assertEquals("", ResultFormat.formatSize(0))
        assertEquals("1 KB", ResultFormat.formatSize(100))
        assertEquals("234 KB", ResultFormat.formatSize(240_000))
        assertEquals("2.0 MB", ResultFormat.formatSize(2L * 1024 * 1024))
    }

    @Test
    fun `file categories`() {
        assertEquals(FileCategory.PDF, FileCategory.of("PDF"))
        assertEquals(FileCategory.MARKDOWN, FileCategory.of("md"))
        assertEquals(FileCategory.TEXT, FileCategory.of("txt"))
        assertEquals(FileCategory.CODE, FileCategory.of(".json"))
        assertEquals(FileCategory.DOCUMENT, FileCategory.of("docx"))
        assertEquals(FileCategory.OTHER, FileCategory.of("zip"))
    }
}
