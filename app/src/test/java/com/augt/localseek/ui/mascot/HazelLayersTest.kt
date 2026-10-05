package com.augt.localseek.ui.mascot

import com.augt.localseek.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HazelLayersTest {
    @Test fun happyAndWorkingHoldTheMagnifierBehindNothing() {
        val expected = listOf(R.drawable.hazel_tail, R.drawable.hazel_body, R.drawable.hazel_lens)
        assertEquals(expected, hazelLayers(HazelMood.HAPPY))
        assertEquals(expected, hazelLayers(HazelMood.WORKING))
    }

    @Test fun emptyMoodHasRestingPawsAndNoLens() {
        val layers = hazelLayers(HazelMood.EMPTY)
        assertEquals(R.drawable.hazel_paws_rest, layers.last())
        assertFalse(R.drawable.hazel_lens in layers)
    }

    @Test fun tailIsAlwaysTheBackLayer() {
        HazelMood.values().forEach { assertEquals(R.drawable.hazel_tail, hazelLayers(it).first()) }
    }
}
