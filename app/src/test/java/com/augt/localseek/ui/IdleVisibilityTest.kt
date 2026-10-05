package com.augt.localseek.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleVisibilityTest {
    @Test fun thresholdsAreTheDocumentedConstants() {
        assertEquals(360f, IdleMascotMinHeightDp)
        assertEquals(200f, IdleHeadingMinHeightDp)
    }

    @Test fun tallAreaShowsEverything() {
        assertEquals(IdleVisibility(mascot = true, heading = true), idleVisibility(700f))
        assertEquals(IdleVisibility(mascot = true, heading = true), idleVisibility(360f))
    }

    @Test fun keyboardOpenHidesMascotButKeepsHeading() {
        val v = idleVisibility(359.9f)
        assertFalse(v.mascot); assertTrue(v.heading)
        assertEquals(IdleVisibility(mascot = false, heading = true), idleVisibility(200f))
    }

    @Test fun veryShortAreaKeepsOnlyChips() {
        assertEquals(IdleVisibility(mascot = false, heading = false), idleVisibility(199.9f))
        assertEquals(IdleVisibility(mascot = false, heading = false), idleVisibility(0f))
    }
}
