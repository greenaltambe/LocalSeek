package com.augt.localseek

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseInfoTest {
    @Test
    fun `the app is version 1_0_0 with version code 2`() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertEquals(2, BuildConfig.VERSION_CODE)
    }
}
