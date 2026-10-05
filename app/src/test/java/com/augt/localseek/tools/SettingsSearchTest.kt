package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val search = SettingsSearch()

    private fun titles(q: String, limit: Int = 3) = search.search(q, limit).map { it.title }

    @Test fun indexIsSaneAndWorksOnMinSdk() {
        val entries = SettingsIndex.ENTRIES
        assertTrue(entries.all { it.sinceApi <= SettingsIndex.MIN_SDK })
        assertTrue(entries.all { it.title.isNotBlank() && it.action.startsWith("android.settings.") })
        assertEquals("duplicate titles", entries.size, entries.map { it.title }.toSet().size)
        assertTrue(entries.size >= 30)
    }

    @Test fun exactTitleAndSynonymMatches() {
        assertEquals("Wi-Fi", titles("wifi").first())
        assertEquals("Wi-Fi", titles("wi-fi").first())
        assertEquals("Bluetooth", titles("bluetooth").first())
        assertEquals("Developer options", titles("developer").first())
        assertEquals("Developer options", titles("usb debugging").first())
        assertEquals("Display", titles("brightness").first())
        assertEquals("Date & time", titles("timezone").first())
    }

    @Test fun prefixMatchingNeedsThreeCharacters() {
        assertEquals("Bluetooth", titles("bluet").first())
        assertEquals("Accessibility", titles("accessib").first())
        assertTrue(titles("b").isEmpty())
        assertTrue(titles("ba").none { it == "Bluetooth" })
    }

    @Test fun titleBeatsSynonymAndAllTokensMustMatch() {
        assertEquals("Battery saver", titles("battery").first())
        assertTrue(titles("battery").contains("Battery optimization"))
        assertEquals("Battery optimization", titles("battery optimization").first())
        assertTrue(titles("wifi quarterly report").isEmpty())
    }

    @Test fun ordinaryFileQueriesDoNotMatch() {
        assertTrue(titles("machine learning tutorials").isEmpty())
        assertTrue(titles("tax return 2024").isEmpty())
        assertTrue(titles("").isEmpty())
        assertTrue(titles("   ").isEmpty())
    }

    @Test fun respectsLimitAndIsDeterministic() {
        assertEquals(2, search.search("app", 2).size)
        assertEquals(titles("app"), titles("app"))
    }
}
