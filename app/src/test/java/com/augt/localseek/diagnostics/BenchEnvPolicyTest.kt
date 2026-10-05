package com.augt.localseek.diagnostics

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchEnvPolicyTest {
    @Test fun airplaneModeOffIsAccepted() = assertNull(BenchEnvPolicy.refusal(0))

    @Test fun airplaneModeOnIsRefusedWithAClearMessage() {
        val m = BenchEnvPolicy.refusal(1)
        assertNotNull(m)
        assertTrue(m!!.contains("airplane_mode_on=1") && m.contains("refusing to start"))
    }

    @Test fun unreadableAirplaneModeIsRefused() = assertNotNull(BenchEnvPolicy.refusal(null))

    @Test fun freezerNoteMentionsTheFreezer() = assertTrue(BenchEnvPolicy.FREEZER_NOTE.contains("freezer"))
}
