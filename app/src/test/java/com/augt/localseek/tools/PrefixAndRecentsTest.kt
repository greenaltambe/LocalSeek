package com.augt.localseek.tools

import com.augt.localseek.ui.theme.animationsEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixAndRecentsTest {

    private val defaults = Aliases.DEFAULTS

    // ---- prefix chip detection ----

    @Test fun chipRequiresTriggerThenSpace() {
        assertNull(Aliases.matchPrefixChip("g", defaults))
        assertNull(Aliases.matchPrefixChip("gadget", defaults))
        val m = Aliases.matchPrefixChip("g weather today", defaults)!!
        assertEquals("g", m.alias.trigger)
        assertEquals("weather today", m.argument)
    }

    @Test fun colonFormTriggersOnlyWhenTheColonDirectlyFollows() {
        val bare = Aliases.matchPrefixChip("c:", defaults)!!
        assertEquals(Alias.SCOPE_CONTACTS, bare.alias.target)
        assertEquals("", bare.argument)
        assertEquals("anna", Aliases.matchPrefixChip("c:anna", defaults)!!.argument)
        assertEquals("anna", Aliases.matchPrefixChip("c: anna", defaults)!!.argument)
        // letters alone, or a space before the colon, are plain text
        assertNull(Aliases.matchPrefixChip("c anna", defaults))
        assertNull(Aliases.matchPrefixChip("c ", defaults))
        assertNull(Aliases.matchPrefixChip("a note", defaults))
        assertNull(Aliases.matchPrefixChip("c :anna", defaults))
        assertNull(Aliases.matchPrefixChip("cat: x", defaults))
    }

    @Test fun longestTriggerWinsAndCaseIsIgnored() {
        assertEquals("ddg", Aliases.matchPrefixChip("DDG hello", defaults)!!.alias.trigger)
    }

    @Test fun calculatorPrefixWorksAsChipWithSpace() {
        val m = Aliases.matchPrefixChip("= 12*7", defaults)!!
        assertEquals(Alias.CALCULATOR, m.alias.target)
        assertEquals("12*7", m.argument)
    }

    // ---- scoped defaults ----

    @Test fun freshDefaultsContainTheScopedPrefixes() {
        val byTrigger = defaults.associateBy { it.trigger }
        assertEquals(Alias.SCOPE_APPS, byTrigger["a:"]?.target)
        assertEquals(Alias.SCOPE_CONTACTS, byTrigger["c:"]?.target)
        assertEquals(Alias.SCOPE_FILES, byTrigger["f:"]?.target)
        assertEquals(Alias.SCOPE_IMAGES, byTrigger["i:"]?.target)
        assertEquals(Alias.SCOPE_SETTINGS, byTrigger["s:"]?.target)
        assertNull(byTrigger["a"])
        assertEquals(defaults.size, defaults.map { it.trigger.lowercase() }.toSet().size)
    }

    @Test fun migrationAddsScopedDefaultsWithoutOverwritingUserPrefixes() {
        val stored = listOf(Alias("g", "google"), Alias("c:", "wikipedia"), Alias("x", Alias.SCOPE_FILES))
        val (merged, skipped) = Aliases.withScopedDefaults(stored)
        // "c:" is taken by the user's own prefix: skipped, never replaced
        assertTrue("c:" in skipped)
        assertEquals("wikipedia", merged.first { it.trigger == "c:" }.target)
        // the files scope is already reachable through the user's "x": that default is skipped too
        assertTrue("f:" in skipped)
        assertFalse(merged.any { it.trigger == "f:" })
        // the rest are added
        assertEquals(Alias.SCOPE_APPS, merged.first { it.trigger == "a:" }.target)
        assertEquals(Alias.SCOPE_IMAGES, merged.first { it.trigger == "i:" }.target)
        assertEquals(Alias.SCOPE_SETTINGS, merged.first { it.trigger == "s:" }.target)
        // everything the user had is still there, in order
        assertEquals(stored, merged.take(3))
    }

    @Test fun scopeAliasesRoundTripThroughJson() {
        val json = Aliases.toJson(defaults)
        assertEquals(defaults, Aliases.fromJson(json))
        assertTrue(Aliases.fromJson(json)!!.first { it.trigger == "a:" }.isScope)
    }

    // ---- colon migration ----

    @Test fun legacyDefaultsAreReplacedInPlaceByTheColonForms() {
        val stored = listOf(Alias("g", "google"), Alias("a", Alias.SCOPE_APPS), Alias("c", Alias.SCOPE_CONTACTS), Alias("=", Alias.CALCULATOR))
        val (out, replaced) = Aliases.migrateLegacyScopedDefaults(stored)
        assertEquals(2, replaced)
        assertEquals(listOf("g", "a:", "c:", "="), out.map { it.trigger })
        assertEquals(Alias.SCOPE_APPS, out[1].target)
    }

    @Test fun editedOrUserMadePrefixesAreNeverTouchedByTheMigration() {
        // "a" points somewhere else now (edited), "f" is a user's engine prefix, "i" already has its colon twin
        val stored = listOf(
            Alias("a", "google"), Alias("f", "wikipedia"), Alias("i", Alias.SCOPE_IMAGES), Alias("i:", "youtube")
        )
        val (out, replaced) = Aliases.migrateLegacyScopedDefaults(stored)
        assertEquals(0, replaced)
        assertEquals(stored, out)
    }

    @Test fun migrationIsIdempotent() {
        val once = Aliases.migrateLegacyScopedDefaults(listOf(Alias("s", Alias.SCOPE_SETTINGS))).first
        val (twice, replaced) = Aliases.migrateLegacyScopedDefaults(once)
        assertEquals(0, replaced)
        assertEquals(once, twice)
    }

    // ---- recent searches ----

    @Test fun recentsAreNewestFirstDedupedAndCapped() {
        var list = emptyList<String>()
        for (i in 1..15) list = RecentSearches.add(list, "query $i")
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("query 15", list.first())
        list = RecentSearches.add(list, "QUERY 12")
        assertEquals("QUERY 12", list.first())
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals(1, list.count { it.equals("query 12", ignoreCase = true) })
    }

    @Test fun blankQueriesAreNotRecorded() {
        assertEquals(listOf("a"), RecentSearches.add(listOf("a"), "   "))
    }

    @Test fun recentsJsonRoundTripAndLenientDecode() {
        val list = listOf("one", "two")
        assertEquals(list, RecentSearches.fromJson(RecentSearches.toJson(list)))
        assertNull(RecentSearches.fromJson("not json"))
        assertEquals(listOf("x"), RecentSearches.fromJson("[\"x\", \"\", \"X\"]"))
        assertEquals(listOf("b"), RecentSearches.remove(listOf("a", "b"), "A"))
    }

    // ---- engine icons ----

    @Test fun defaultEnginesGetSensibleIconsAndCustomOnesALetterTile() {
        assertEquals("search", WebEngines.DEFAULTS.first { it.id == "google" }.effectiveIconKey)
        assertEquals("shield", WebEngines.DEFAULTS.first { it.id == "duckduckgo" }.effectiveIconKey)
        assertEquals("video", WebEngines.DEFAULTS.first { it.id == "youtube" }.effectiveIconKey)
        assertEquals("book", WebEngines.DEFAULTS.first { it.id == "wikipedia" }.effectiveIconKey)
        assertEquals(EngineIcons.LETTER, WebEngine("custom_x", "X", "https://x.test/%s").effectiveIconKey)
    }

    @Test fun iconKeyIsPersistedAndOldDataWithoutItStillLoads() {
        val withIcon = WebEngines.custom("Maps", "https://m.test/?q=%s", emptyList(), iconKey = "map")!!
        assertEquals("map", WebEngines.fromJson(WebEngines.toJson(listOf(withIcon)))!!.single().iconKey)
        val old = """[{"id":"a","name":"A","template":"https://a.test/%s"}]"""
        val loaded = WebEngines.fromJson(old)!!.single()
        assertNull(loaded.iconKey)
        assertEquals(EngineIcons.LETTER, loaded.effectiveIconKey)
    }

    @Test fun unknownIconKeysAreDropped() {
        assertNull(WebEngines.custom("N", "https://n.test/%s", emptyList(), iconKey = "bogus")!!.iconKey)
        assertNull(WebEngines.fromJson("""[{"id":"a","name":"A","template":"https://a.test/%s","icon":"bogus"}]""")!!.single().iconKey)
    }

    @Test fun pickerOffersAboutThirtyKeysIncludingLetterTile() {
        assertEquals(31, EngineIcons.KEYS.size) // 30 generic icons + shield (DuckDuckGo)
        assertEquals(EngineIcons.LETTER, EngineIcons.KEYS.first())
        assertEquals(EngineIcons.KEYS.size, EngineIcons.KEYS.toSet().size)
    }

    // ---- contact email action ----

    @Test fun emailActionOnlyWhenTheContactHasAnAddress() {
        val withMail = ContactActions.build("+15551234567", emptySet(), email = "a@b.test")
        assertEquals(ContactActionKind.EMAIL, withMail.last().kind)
        assertEquals("mailto:a@b.test", withMail.last().uri)
        assertFalse(ContactActions.build("+15551234567", emptySet()).any { it.kind == ContactActionKind.EMAIL })
        assertTrue(ContactActions.build("+15551234567", emptySet(), email = "not an email").none { it.kind == ContactActionKind.EMAIL })
        val mailOnly = ContactActions.build(null, emptySet(), email = "a@b.test")
        assertEquals(listOf(ContactActionKind.EMAIL), mailOnly.map { it.kind })
        assertNotNull(mailOnly.single().intentAction)
    }

    // ---- reduced motion ----

    @Test fun animationsAreOffOnlyWhenTheScaleIsZero() {
        assertFalse(animationsEnabled(0f))
        assertTrue(animationsEnabled(1f))
        assertTrue(animationsEnabled(0.5f))
    }
}
