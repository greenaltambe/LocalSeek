package com.augt.localseek.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperUnlockTest {

    @Test
    fun `seven quick taps unlock`() {
        val unlock = DeveloperUnlock()
        var t = 1_000L
        repeat(6) { assertFalse(unlock.onTap(t)); t += 300 }
        assertTrue(unlock.onTap(t))
    }

    @Test
    fun `a pause resets the sequence`() {
        val unlock = DeveloperUnlock()
        var t = 0L
        repeat(5) { unlock.onTap(t); t += 300 }
        t += 5_000 // too slow
        repeat(6) { assertFalse(unlock.onTap(t)); t += 300 }
        assertTrue(unlock.onTap(t))
    }

    @Test
    fun `six taps are not enough and the counter restarts after an unlock`() {
        val unlock = DeveloperUnlock()
        var t = 0L
        repeat(6) { assertFalse(unlock.onTap(t)); t += 100 }
        assertEquals(1, unlock.remaining)
        assertTrue(unlock.onTap(t))
        t += 100
        assertFalse(unlock.onTap(t))
        assertEquals(DeveloperUnlock.TAPS_NEEDED - 1, unlock.remaining)
    }
}
