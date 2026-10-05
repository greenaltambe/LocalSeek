package com.augt.localseek.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val full = SettingsSnapshot(
        aliases = Aliases.DEFAULTS,
        engines = WebEngines.DEFAULTS,
        pins = listOf(Pin("APP", "com.example"), Pin("FILE", "abc")),
        theme = ThemeSettings(ThemeMode.DARK, AccentPreset.GREEN, true)
    )

    private fun ok(json: String) = SettingsBackup.parse(json) as BackupParseResult.Ok
    private fun invalid(json: String) = (SettingsBackup.parse(json) as BackupParseResult.Invalid).reason

    @Test fun roundTrip() {
        val r = ok(SettingsBackup.export(full))
        assertEquals(full, r.snapshot)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun exportCarriesVersionAndOnlyWhitelistedKeys() {
        val o = JSONObject(SettingsBackup.export(full))
        assertEquals(1, o.getInt("schemaVersion"))
        assertEquals("localseek-settings", o.getString("format"))
        assertEquals(SettingsBackup.ALLOWED_KEYS, o.keys().asSequence().toSet())
    }

    @Test fun absentSectionsStayNullSoImportLeavesThemAlone() {
        val r = ok("""{"format":"localseek-settings","schemaVersion":1,"theme":{"mode":"LIGHT"}}""")
        assertNull(r.snapshot.aliases); assertNull(r.snapshot.engines); assertNull(r.snapshot.pins)
        assertEquals(ThemeMode.LIGHT, r.snapshot.theme!!.mode)
    }

    @Test fun rejectsWrongFormatVersionAndGarbage() {
        assertTrue(invalid("nope").contains("valid JSON"))
        assertTrue(invalid("[]").contains("valid JSON"))
        assertTrue(invalid("""{"schemaVersion":1}""").contains("Not a LocalSeek"))
        assertTrue(invalid("""{"format":"other","schemaVersion":1}""").contains("Not a LocalSeek"))
        assertTrue(invalid("""{"format":"localseek-settings"}""").contains("schema version"))
        assertTrue(invalid("""{"format":"localseek-settings","schemaVersion":0}""").contains("schema version"))
        assertTrue(invalid("""{"format":"localseek-settings","schemaVersion":2}""").contains("newer"))
    }

    @Test fun rejectsUnexpectedFieldsSoSecretsOrIndexDataCannotRideAlong() {
        val r = invalid("""{"format":"localseek-settings","schemaVersion":1,"apiKey":"sk-123","index":[1]}""")
        assertTrue(r.contains("apiKey"))
    }

    @Test fun rejectsWrongSectionTypes() {
        val head = """{"format":"localseek-settings","schemaVersion":1,"""
        assertTrue(invalid(head + """"engines":"x"}""").contains("engines"))
        assertTrue(invalid(head + """"aliases":{}}""").contains("aliases"))
        assertTrue(invalid(head + """"pins":5}""").contains("pins"))
        assertTrue(invalid(head + """"theme":[]}""").contains("theme"))
        assertTrue(invalid(head + """"engines":[{"id":"a","name":"A","template":"javascript:%s"}]}""").contains("no valid engine"))
    }

    @Test fun dropsBadEntriesWithWarnings() {
        val r = ok(
            """{"format":"localseek-settings","schemaVersion":1,
            "engines":[{"id":"a","name":"A","template":"https://a.test/%s"},{"id":"b","name":"B","template":"file:///%s"}],
            "aliases":[{"trigger":"x","target":"a"},{"trigger":"has space","target":"a"}],
            "pins":[{"entityType":"APP","stableKey":"k"},{"entityType":"IMAGE","stableKey":"k"}]}"""
        )
        assertEquals(1, r.snapshot.engines!!.size)
        assertEquals(1, r.snapshot.aliases!!.size)
        assertEquals(1, r.snapshot.pins!!.size)
        assertEquals(3, r.warnings.size)
    }

    @Test fun enforcesSizeLimits() {
        assertTrue(invalid("x".repeat(SettingsBackup.MAX_BYTES + 1)).contains("too large"))
        val many = (1..SettingsBackup.MAX_ALIASES + 1).joinToString(",", "[", "]") { """{"trigger":"t$it","target":"calc"}""" }
        assertTrue(invalid("""{"format":"localseek-settings","schemaVersion":1,"aliases":$many}""").contains("Too many"))
    }

    @Test fun pruneDeadAliases() {
        val aliases = listOf(Alias("g", "google"), Alias("=", Alias.CALCULATOR), Alias("x", "gone"))
        val (kept, dropped) = SettingsBackup.pruneDeadAliases(aliases, WebEngines.DEFAULTS)
        assertEquals(listOf(Alias("g", "google"), Alias("=", Alias.CALCULATOR)), kept)
        assertEquals(1, dropped)
    }

    @Test fun contactPinsAreExcludedFromExport() {
        val snapshot = SettingsSnapshot(
            pins = listOf(
                Pin("APP", "com.example"),
                Pin("CONTACT", "lookup/123/Alice"),
                Pin("FILE", "/sdcard/doc.pdf")
            )
        )
        val exported = SettingsBackup.export(snapshot)
        val o = JSONObject(exported)
        val pins = o.getJSONArray("pins")
        assertEquals(2, pins.length())
        for (i in 0 until pins.length()) {
            val pin = pins.getJSONObject(i)
            assertTrue(pin.getString("entityType") != "CONTACT")
            assertTrue(!pin.getString("stableKey").contains("Alice"))
        }
        assertTrue(!exported.contains("CONTACT"))
        assertTrue(!exported.contains("Alice"))
    }

    @Test fun contactPinsInImportAreDroppedWithWarning() {
        val r = ok(
            """{"format":"localseek-settings","schemaVersion":1,
            "pins":[{"entityType":"APP","stableKey":"k"},{"entityType":"CONTACT","stableKey":"123"}]}"""
        )
        assertEquals(1, r.snapshot.pins!!.size)
        assertEquals("APP", r.snapshot.pins!![0].entityType)
        assertTrue(r.warnings.any { it.contains("pin(s) skipped") })
    }
}
