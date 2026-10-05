package com.augt.localseek.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentityUtilsTest {

    @Test
    fun contactStableKey_withValidLookupKey_returnsLookupKey() {
        val lookupKey = "0r1-42352134A"
        val stableKey = IdentityUtils.contactStableKey(lookupKey)
        assertEquals(lookupKey, stableKey)
    }

    @Test
    fun contactStableKey_withNullLookupKey_throwsIllegalArgumentException() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            IdentityUtils.contactStableKey(null)
        }
        assertEquals("Contact lookupKey must not be null or blank", ex.message)
    }

    @Test
    fun contactStableKey_withEmptyOrBlankLookupKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            IdentityUtils.contactStableKey("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IdentityUtils.contactStableKey("   ")
        }
    }

    @Test
    fun fileStableKey_returnsDeterministicSha1() {
        val path = "/sdcard/Documents/notes.txt"
        val expected = IdentityUtils.sha1(path)
        assertEquals(expected, IdentityUtils.fileStableKey(path))
    }

    @Test
    fun appStableKey_returnsPackageName() {
        val pkg = "com.google.android.apps.messaging"
        assertEquals(pkg, IdentityUtils.appStableKey(pkg))
    }

    @Test
    fun imageStableKey_returnsPrefixedMediaStoreId() {
        val id = 12345L
        assertEquals("media:12345", IdentityUtils.imageStableKey(id))
    }
}
