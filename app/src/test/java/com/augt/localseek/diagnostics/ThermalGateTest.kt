package com.augt.localseek.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ThermalGateTest {
    @Test fun noWaitWhenAlreadyLight() {
        var now = 0L
        val r = ThermalGate.await({ 1 }, { now }, { now += it })
        assertEquals(ThermalGate.Result(0, 1, false), r)
    }

    @Test fun waitsUntilStatusDrops() {
        var now = 0L
        val seq = ArrayDeque(listOf(2, 2, 2, 1))
        val r = ThermalGate.await({ seq.removeFirst() }, { now }, { now += it })
        assertFalse(r.timedOut)
        assertEquals(30_000L, r.waitedMs)
        assertEquals(1, r.status)
    }

    @Test fun timesOutAfterTheCapAndReportsTheStatus() {
        var now = 0L
        val r = ThermalGate.await({ 3 }, { now }, { now += it })
        assertTrue(r.timedOut)
        assertEquals(ThermalGate.MAX_WAIT_MS, r.waitedMs)
        assertEquals(3, r.status)
    }

    @Test fun headerOnlyPoolIsRefused() {
        try { PoolGuard.check("x.csv", listOf("a,b")); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("header only")) }
        try { PoolGuard.check("x.csv", emptyList()); fail() } catch (_: IllegalStateException) {}
        PoolGuard.check("x.csv", listOf("a,b", "1,2"))
    }

    @Test fun dirtyBuildIsRefused() {
        assertNotNull(BenchEnvPolicy.dirtyRefusal("abc-dirty"))
        assertNull(BenchEnvPolicy.dirtyRefusal("abcdef0123"))
    }
}
