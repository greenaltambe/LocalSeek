package com.augt.localseek.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class IndexProgressTest {

    @Test
    fun `fraction and percent`() {
        val p = IndexProgress(4200, 6000)
        assertTrue(p.isDeterminate)
        assertEquals(0.7f, p.fraction!!, 0.0001f)
        assertEquals(70, p.percent)
    }

    @Test
    fun `unknown total is indeterminate`() {
        val p = IndexProgress(120, 0)
        assertFalse(p.isDeterminate)
        assertNull(p.fraction)
        assertNull(p.percent)
        assertEquals(120, p.shownDone)
    }

    @Test
    fun `done above total is clamped to the total`() {
        // the index can hold files that no longer exist on the device
        val p = IndexProgress(6500, 6000)
        assertEquals(1f, p.fraction!!, 0f)
        assertEquals(6000, p.shownDone)
    }

    @Test
    fun `text uses locale thousands separators`() {
        assertEquals("4,200" to "6,000", IndexProgressText.counts(IndexProgress(4200, 6000), Locale.US))
        assertEquals("4.200" to "6.000", IndexProgressText.counts(IndexProgress(4200, 6000), Locale.GERMANY))
        assertEquals("120" to null, IndexProgressText.counts(IndexProgress(120, 0), Locale.US))
        assertEquals("2,615", IndexProgressText.count(2615, Locale.US))
    }

    @Test
    fun `missing progress data is not a progress`() {
        assertNull(IndexProgress.fromData(-1, -1))
        assertNull(IndexProgress.fromData(5, -1))
        assertEquals(IndexProgress(5, 10), IndexProgress.fromData(5, 10))
    }
}
