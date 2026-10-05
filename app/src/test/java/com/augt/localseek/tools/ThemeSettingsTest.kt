package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    @Test fun darkModeResolution() {
        assertTrue(ThemeSettings(mode = ThemeMode.SYSTEM).isDark(systemDark = true))
        assertFalse(ThemeSettings(mode = ThemeMode.SYSTEM).isDark(systemDark = false))
        assertFalse(ThemeSettings(mode = ThemeMode.LIGHT).isDark(systemDark = true))
        assertTrue(ThemeSettings(mode = ThemeMode.DARK).isDark(systemDark = false))
    }

    @Test fun dynamicColourNeedsApi31AndOptIn() {
        val on = ThemeSettings(dynamicColor = true)
        assertFalse(on.useDynamic(30))
        assertTrue(on.useDynamic(31))
        assertTrue(on.useDynamic(35))
        assertFalse(ThemeSettings(dynamicColor = false).useDynamic(35))
    }

    @Test fun defaultsPreserveTheOriginalLook() {
        val d = ThemeSettings()
        assertEquals(ThemeMode.SYSTEM, d.mode)
        assertEquals(AccentPreset.PURPLE, d.accent)
        assertFalse(d.dynamicColor)
    }

    @Test fun jsonRoundTripAndFallbacks() {
        val t = ThemeSettings(ThemeMode.DARK, AccentPreset.GREEN, true)
        assertEquals(t, ThemeSettings.fromJson(ThemeSettings.toJson(t)))
        assertEquals(ThemeSettings(), ThemeSettings.fromJson("""{"mode":"PINK","accent":"NOPE"}"""))
        assertEquals(ThemeSettings(mode = ThemeMode.LIGHT), ThemeSettings.fromJson("""{"mode":"LIGHT"}"""))
        assertNull(ThemeSettings.fromJson("nonsense"))
    }
}
