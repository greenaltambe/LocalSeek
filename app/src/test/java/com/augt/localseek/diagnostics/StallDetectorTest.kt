package com.augt.localseek.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {
    @Test fun stallsOnlyAfterTheTimeoutAndTouchResetsIt() {
        var now = 1_000L
        val d = StallDetector(300_000L) { now }
        assertFalse(d.isStalled())
        now += 299_999L
        assertFalse(d.isStalled())
        now += 1L
        assertTrue(d.isStalled())
        d.touch()
        assertFalse(d.isStalled())
    }

    @Test fun framesAreClassAndMethodNamesOnly() {
        val t = Thread("worker-1")
        val lines = StallDetector.frames(mapOf(t to arrayOf(StackTraceElement("a.B", "run", "B.kt", 12), StackTraceElement("c.D", "wait", null, -1))))
        assertEquals(listOf("THREAD worker-1 state=${t.state}", "  at a.B.run", "  at c.D.wait"), lines)
        assertTrue(lines.none { it.contains("B.kt") || it.contains("12") })
    }

    @Test fun defaultTimeoutIsFiveMinutes() = assertEquals(300_000L, StallDetector.DEFAULT_TIMEOUT_MS)
}

class GcGuardAndPhaseTest {
    @Test fun phaseMarkerAndSecondsSinceProgress() {
        var now = 0L
        val d = StallDetector(300_000L) { now }
        assertEquals("init", d.phase)
        d.phase = "gc-begin E1_bm25"
        now = 42_500L
        assertEquals("gc-begin E1_bm25", d.phase)
        assertEquals(42L, d.secondsSinceProgress())
    }

    @Test fun finishedGcIsDoneAndCounted() {
        val g = GcGuard(timeoutMs = 2_000L, gc = {})
        assertEquals(GcGuard.Outcome.DONE, g.request())
        assertEquals(1, g.calls); assertEquals(0, g.timeouts); assertFalse(g.stalled)
    }

    @Test fun stalledGcTimesOutThenLaterRequestsAreSkipped() {
        val release = java.util.concurrent.CountDownLatch(1)
        var called = 0
        val g = GcGuard(timeoutMs = 50L, gc = { called++; release.await() })
        assertEquals(GcGuard.Outcome.TIMEOUT, g.request())
        assertTrue(g.stalled)
        assertEquals(GcGuard.Outcome.SKIPPED, g.request())
        assertEquals(1, called); assertEquals(1, g.calls); assertEquals(1, g.timeouts)
        assertTrue(g.maxMs >= 50L)
        release.countDown()
    }
}
