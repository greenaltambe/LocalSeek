package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ToolResolverTest {

    // 2026-09-30T12:00:00Z
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private val resolver = ToolResolver(clock)

    @Test fun calculatorCardForMath() {
        val card = resolver.resolve("2+3*4")!!
        assertEquals(ToolKind.CALCULATOR, card.kind)
        assertEquals("14", card.value)
        assertEquals("14", card.copyText)
    }

    @Test fun bareQueriesThatLookLikeDatesPhonesOrProseAreNotMath() {
        assertNull(resolver.resolve("2026-09-30"))
        assertNull(resolver.resolve("555-1234"))
        assertNull(resolver.resolve("2024"))
        assertNull(resolver.resolve("report 2+2"))
        assertNull(resolver.resolve("pi"))
        assertNull(resolver.resolve("invoice (2024)"))
    }

    @Test fun spacedMinusAndFunctionsTrigger() {
        assertEquals("2", resolver.resolve("5 - 3")!!.value)
        assertEquals("4", resolver.resolve("sqrt(16)")!!.value)
    }

    @Test fun invalidBareMathIsSilentButExplicitTriggerShowsError() {
        assertNull(resolver.resolve("1/0"))
        val card = resolver.resolve("1/0", forceCalculator = true)!!
        assertTrue(card.isError)
        assertEquals("Division by zero", card.value)
        assertEquals("2", resolver.resolve("5-3", forceCalculator = true)!!.value)
    }

    @Test fun converterCard() {
        val card = resolver.resolve("100 c to f")!!
        assertEquals(ToolKind.CONVERTER, card.kind)
        assertEquals("212 f", card.value)
        assertEquals("212", card.copyText)
    }

    @Test fun daysUntilAndSince() {
        assertEquals("in 93 days", resolver.resolve("days until 2027-01-01")!!.value)
        assertEquals("Today", resolver.resolve("days until 2026-09-30")!!.value)
        assertEquals("1 day ago (date has passed)", resolver.resolve("days until 2026-09-29")!!.value)
        assertEquals("30 days ago", resolver.resolve("days since 2026-08-31")!!.value)
        assertEquals("365 days", resolver.resolve("days between 2026-01-01 and 2027-01-01")!!.value)
    }

    @Test fun invalidDatesAreIgnored() {
        assertNull(resolver.resolve("days until 2027-13-45"))
        assertNull(resolver.resolve("days until tomorrow"))
    }

    @Test fun worldClock() {
        val tokyo = resolver.resolve("now in tokyo")!!
        assertEquals(ToolKind.DATETIME, tokyo.kind)
        assertTrue(tokyo.value, tokyo.value.contains("21:00"))
        assertTrue(tokyo.value.contains("+09:00"))
        assertTrue(resolver.resolve("time in New York")!!.value.contains("08:00"))
        assertTrue(resolver.resolve("time in asia/kolkata")!!.value.contains("17:30"))
        assertNull(resolver.resolve("now in atlantis"))
    }

    @Test fun nowAlone() {
        val card = resolver.resolve("now")!!
        assertTrue(card.value.contains("30 September 2026"))
        assertTrue(card.value.contains("12:00"))
    }

    @Test fun ordinarySearchQueriesProduceNoCard() {
        assertNull(resolver.resolve("machine learning tutorials"))
        assertNull(resolver.resolve("kotlin coroutines example"))
        assertNull(resolver.resolve(""))
        assertFalse(ToolResolver.looksLikeMath("hello"))
        assertNotNull(resolver.resolve("(1+2)*3"))
    }
}
