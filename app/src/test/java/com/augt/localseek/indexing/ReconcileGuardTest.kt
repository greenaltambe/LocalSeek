package com.augt.localseek.indexing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcileGuardTest {

    /** Fake store: ids 1..n; the scan sees [scanned]; records what was deleted. */
    private class Fake(n: Int, val scanned: Set<Int>) {
        val existing = (1..n).toList()
        val deleted = mutableListOf<Int>()
        suspend fun run(access: Boolean) = ReconcileGuard.reconcile(
            accessGranted = access, scannedCount = scanned.size, existing = existing,
            isMissing = { it !in scanned }
        ) { deleted.addAll(it) }
    }

    @Test
    fun `normal case still deletes the missing items`() = runBlocking {
        val f = Fake(10, (1..8).toSet())          // 2 of 10 vanished
        val o = f.run(access = true)
        assertEquals(ReconcileDecision.DELETE, o.decision)
        assertEquals(listOf(9, 10), f.deleted)
        assertEquals(2, o.deleted)
        assertNull(o.decision.reason)
    }

    @Test
    fun `nothing missing deletes nothing`() = runBlocking {
        val f = Fake(5, (1..5).toSet())
        assertEquals(ReconcileDecision.DELETE, f.run(true).decision)
        assertTrue(f.deleted.isEmpty())
    }

    @Test
    fun `revoked or reduced access never deletes`() = runBlocking {
        val f = Fake(10, (1..9).toSet())          // would be a normal 1-item deletion
        val o = f.run(access = false)
        assertEquals(ReconcileDecision.KEEP_NO_ACCESS, o.decision)
        assertTrue(f.deleted.isEmpty())
        assertEquals("index kept: storage access changed", o.decision.reason)
    }

    @Test
    fun `empty walk with a non-empty database never deletes`() = runBlocking {
        val f = Fake(10, emptySet())
        assertEquals(ReconcileDecision.KEEP_EMPTY_SCAN, f.run(true).decision)
        assertTrue(f.deleted.isEmpty())
        assertNotNull(ReconcileDecision.KEEP_EMPTY_SCAN.reason)
    }

    @Test
    fun `empty walk with an empty database is fine`() = runBlocking {
        val f = Fake(0, emptySet())
        assertEquals(ReconcileDecision.DELETE, f.run(true).decision)
        assertTrue(f.deleted.isEmpty())
    }

    @Test
    fun `mass deletion above half is refused, exactly half and below proceeds`() = runBlocking {
        val over = Fake(10, (1..4).toSet())       // 6 of 10 would go
        assertEquals(ReconcileDecision.KEEP_MASS_DELETE, over.run(true).decision)
        assertTrue(over.deleted.isEmpty())
        val half = Fake(10, (1..5).toSet())       // exactly 50%: allowed
        assertEquals(ReconcileDecision.DELETE, half.run(true).decision)
        assertEquals(5, half.deleted.size)
        val one = Fake(1, emptySet())             // a single item with an empty scan: refused (empty-scan rule)
        assertEquals(ReconcileDecision.KEEP_EMPTY_SCAN, one.run(true).decision)
    }

    @Test
    fun `partial photo access is detected only on Android 14 without full access`() {
        assertTrue(ReconcileGuard.isPartialPhotoAccess(34, hasReadMediaImages = false, hasVisualUserSelected = true))
        assertFalse(ReconcileGuard.isPartialPhotoAccess(34, hasReadMediaImages = true, hasVisualUserSelected = true))
        assertFalse(ReconcileGuard.isPartialPhotoAccess(34, hasReadMediaImages = false, hasVisualUserSelected = false))
        assertFalse(ReconcileGuard.isPartialPhotoAccess(33, hasReadMediaImages = false, hasVisualUserSelected = true))
    }

    @Test
    fun `photo reconcile under partial access keeps everything, full access deletes`() = runBlocking {
        val existing = (1L..10L).toList()
        val scanned = (1L..9L).toSet()
        val deleted = mutableListOf<Long>()
        val partial = ReconcileGuard.isPartialPhotoAccess(34, false, true)
        ReconcileGuard.reconcile(!partial, scanned.size, existing, { it !in scanned }) { deleted.addAll(it) }
        assertTrue(deleted.isEmpty())
        val full = ReconcileGuard.isPartialPhotoAccess(34, true, true)
        ReconcileGuard.reconcile(!full, scanned.size, existing, { it !in scanned }) { deleted.addAll(it) }
        assertEquals(listOf(10L), deleted)
    }
}
