package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hostile or awkward input for the settings importer: nothing may throw and nothing invalid may be applied. */
class SettingsBackupEdgeCasesTest {

    private fun valid(): String = SettingsBackup.export(
        SettingsSnapshot(engines = WebEngines.DEFAULTS, aliases = Aliases.DEFAULTS, pins = emptyList(), theme = ThemeSettings())
    )

    private fun isInvalid(json: String) = SettingsBackup.parse(json) is BackupParseResult.Invalid

    @Test fun emptyAndWhitespaceInputAreInvalidNotCrashes() {
        assertTrue(isInvalid(""))
        assertTrue(isInvalid("   \n "))
    }

    @Test fun topLevelArrayOrScalarIsInvalid() {
        assertTrue(isInvalid("[]"))
        assertTrue(isInvalid("42"))
        assertTrue(isInvalid("null"))
    }

    @Test fun leadingByteOrderMarkIsAccepted() {
        assertTrue(SettingsBackup.parse(0xFEFF.toChar() + valid()) is BackupParseResult.Ok)
    }

    @Test fun nullSectionsAreRejectedRatherThanTreatedAsEmpty() {
        val base = """{"format":"localseek-settings","schemaVersion":1,"""
        assertTrue(isInvalid(base + """"engines":null}"""))
        assertTrue(isInvalid(base + """"aliases":"x"}"""))
        assertTrue(isInvalid(base + """"theme":[]}"""))
    }

    @Test fun headerOnlyFileImportsNothing() {
        val r = SettingsBackup.parse("""{"format":"localseek-settings","schemaVersion":1}""") as BackupParseResult.Ok
        assertEquals(SettingsSnapshot(), r.snapshot)
    }

    @Test fun duplicateEnginesAndAliasTriggersAreCollapsed() {
        val json = """{"format":"localseek-settings","schemaVersion":1,
            "engines":[{"id":"a","name":"A","template":"https://a.example/?q=%s"},
                       {"id":"a","name":"A2","template":"https://a2.example/?q=%s"}],
            "aliases":[{"trigger":"GH","target":"a"},{"trigger":"gh","target":"a"}]}"""
        val r = SettingsBackup.parse(json) as BackupParseResult.Ok
        assertEquals(1, r.snapshot.engines!!.size)
        assertEquals(1, r.snapshot.aliases!!.size)
    }

    @Test fun unsafeEngineTemplatesAreDropped() {
        val json = """{"format":"localseek-settings","schemaVersion":1,
            "engines":[{"id":"ok","name":"Ok","template":"https://ok.example/?q=%s"},
                       {"id":"js","name":"Js","template":"javascript:alert(%s)"},
                       {"id":"file","name":"File","template":"file:///sdcard/%s"}]}"""
        val r = SettingsBackup.parse(json) as BackupParseResult.Ok
        assertEquals(listOf("ok"), r.snapshot.engines!!.map { it.id })
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test fun allEnginesInvalidRejectsWholeFileSoDefaultsSurvive() {
        val json = """{"format":"localseek-settings","schemaVersion":1,
            "engines":[{"id":"js","name":"Js","template":"javascript:%s"}]}"""
        assertTrue(isInvalid(json))
    }

    @Test fun pruneKeepsCalculatorAliasesWhenEnginesAreEmpty() {
        val (kept, dropped) = SettingsBackup.pruneDeadAliases(
            listOf(Alias("=", Alias.CALCULATOR), Alias("g", "google")), emptyList()
        )
        assertEquals(listOf(Alias("=", Alias.CALCULATOR)), kept)
        assertEquals(1, dropped)
    }
}
