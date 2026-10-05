package com.augt.localseek.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasesAndEnginesTest {

    private val aliases = Aliases.DEFAULTS
    private val engines = WebEngines.DEFAULTS

    // ---- template expansion ----

    @Test fun expandEncodesSpacesAndSpecialCharacters() {
        assertEquals("https://x.test/?q=hello%20world", WebEngines.expand("https://x.test/?q=%s", "hello world"))
        assertEquals("https://x.test/?q=a%26b%3Dc%3Fd%23e", WebEngines.expand("https://x.test/?q=%s", "a&b=c?d#e"))
        assertEquals("https://x.test/?q=100%25%20%2B%201", WebEngines.expand("https://x.test/?q=%s", "100% + 1"))
        assertEquals("https://x.test/?q=caf%C3%A9%20%E6%97%A5%E6%9C%AC", WebEngines.expand("https://x.test/?q=%s", "café 日本"))
        assertEquals("https://x.test/?q=%22quoted%22%20%2Fpath%5Cx", WebEngines.expand("https://x.test/?q=%s", "\"quoted\" /path\\x"))
    }

    @Test fun expandIsLiteralForDollarAndBackslashAndReplacesAllPlaceholders() {
        assertEquals("https://x.test/%24100/%24100", WebEngines.expand("https://x.test/%s/%s", "\$100"))
        assertEquals("https://x.test/?q=trimmed", WebEngines.expand("https://x.test/?q=%s", "  trimmed  "))
    }

    @Test fun templateValidation() {
        assertTrue(WebEngines.isValidTemplate("https://example.com/search?q=%s"))
        assertTrue(WebEngines.isValidTemplate("http://example.com/%s"))
        assertFalse(WebEngines.isValidTemplate("https://example.com/search"))            // no %s
        assertFalse(WebEngines.isValidTemplate("javascript:alert(%s)"))
        assertFalse(WebEngines.isValidTemplate("intent://x#Intent;scheme=http;end?%s"))
        assertFalse(WebEngines.isValidTemplate("file:///sdcard/%s"))
        assertFalse(WebEngines.isValidTemplate("https://%s.example.com/"))               // query must not pick the host
        assertFalse(WebEngines.isValidTemplate("https://example.com/a b?q=%s"))
        assertFalse(WebEngines.isValidTemplate(""))
    }

    @Test fun defaultsAreValidAndAlwaysProduceHttpsUrls() {
        engines.forEach { assertTrue(it.name, WebEngines.isValidTemplate(it.urlTemplate)) }
        val actions = WebEngines.actions(engines, "cats & dogs")
        assertEquals(4, actions.size)
        assertTrue(actions.all { it.url.startsWith("https://") && !it.url.contains(' ') })
        assertEquals(0, WebEngines.actions(engines, "  ").size)
    }

    @Test fun customEngineCreationGeneratesUniqueIds() {
        val a = WebEngines.custom("My Site", "https://my.site/?s=%s", engines)!!
        assertEquals("custom_mysite", a.id)
        val b = WebEngines.custom("My Site", "https://my.site/?s=%s", engines + a)!!
        assertEquals("custom_mysite_2", b.id)
        assertNull(WebEngines.custom("", "https://my.site/?s=%s", engines))
        assertNull(WebEngines.custom("Bad", "ftp://my.site/%s", engines))
    }

    @Test fun engineJsonRoundTripAndLenientDecode() {
        assertEquals(engines, WebEngines.fromJson(WebEngines.toJson(engines)))
        val mixed = """[{"id":"a","name":"A","template":"https://a.test/%s"},{"id":"b","name":"B","template":"nope"},"junk",{"id":"a","name":"dup","template":"https://d.test/%s"}]"""
        assertEquals(listOf(WebEngine("a", "A", "https://a.test/%s")), WebEngines.fromJson(mixed))
        assertNull(WebEngines.fromJson("not json"))
        assertNull(WebEngines.fromJson("{}"))
    }

    // ---- aliases ----

    @Test fun matchesPrefixTriggers() {
        assertEquals(AliasMatch(Alias("g", "google"), "kotlin flow"), Aliases.match("g kotlin flow", aliases))
        assertEquals("cats", Aliases.match("ddg   cats", aliases)!!.argument)
        assertEquals("youtube", Aliases.match("YT lofi", aliases)!!.alias.target)
        assertEquals("wikipedia", Aliases.match("w  Nile", aliases)!!.alias.target)
    }

    @Test fun letterTriggersNeedASeparatorSymbolTriggersDoNot() {
        assertNull(Aliases.match("google maps", aliases))   // starts with "g" but no separator
        assertNull(Aliases.match("g", aliases))             // no argument
        assertNull(Aliases.match("wikipedia", aliases))
        assertEquals("2+2", Aliases.match("=2+2", aliases)!!.argument)
        assertEquals("2+2", Aliases.match("= 2+2", aliases)!!.argument)
        assertNull(Aliases.match("=", aliases))
    }

    @Test fun longestTriggerWins() {
        val list = listOf(Alias("d", "duckduckgo"), Alias("dd", "google"))
        assertEquals("google", Aliases.match("dd foo", list)!!.alias.target)
        assertEquals("duckduckgo", Aliases.match("d foo", list)!!.alias.target)
    }

    @Test fun triggerValidationAndJson() {
        assertTrue(Aliases.isValidTrigger("gh", aliases))
        assertFalse(Aliases.isValidTrigger("G", aliases))            // duplicate, case-insensitive
        assertFalse(Aliases.isValidTrigger("a b", aliases))
        assertFalse(Aliases.isValidTrigger("", aliases))
        assertFalse(Aliases.isValidTrigger("waytoolongtrigger", aliases))
        assertEquals(aliases, Aliases.fromJson(Aliases.toJson(aliases)))
        assertNull(Aliases.fromJson("oops"))
        assertEquals(1, Aliases.fromJson("""[{"trigger":"x","target":"google"},{"trigger":"X","target":"y"},{"trigger":"","target":"z"}]""")!!.size)
    }

    // ---- panel builder ----

    private val builder = ToolsPanelBuilder()

    @Test fun aliasEngineQueryYieldsPrimaryActionOnly() {
        val s = builder.build("g hello world", aliases, engines)
        assertEquals("https://www.google.com/search?q=hello%20world", s.aliasAction!!.url)
        assertNull(s.card)
        assertTrue(s.webActions.isEmpty())
    }

    @Test fun calcAliasAlwaysShowsCardOrError() {
        assertEquals("4", builder.build("= 2+2", aliases, engines).card!!.value)
        assertTrue(builder.build("=1/0", aliases, engines).card!!.isError)
    }

    @Test fun aliasToDeletedEngineFallsBackToNormalSearch() {
        val s = builder.build("yt lofi", aliases, engines.filter { it.id != "youtube" })
        assertNull(s.aliasAction)
        assertEquals(3, s.webActions.size)
    }

    @Test fun settingsMatchesAppearForSettingsQueriesOnly() {
        assertEquals("Wi-Fi", builder.build("wifi", aliases, engines).settingsMatches.first().title)
        assertTrue(builder.build("holiday photos", aliases, engines).settingsMatches.isEmpty())
        assertTrue(builder.build("g wifi", aliases, engines).settingsMatches.isEmpty()) // alias wins
    }

    @Test fun plainQueryShowsFallbackBarAndBlankShowsNothing() {
        val s = builder.build("hello", aliases, engines)
        assertNull(s.card)
        assertEquals(4, s.webActions.size)
        assertTrue(builder.build("", aliases, engines).isEmpty)
        assertNotNull(builder.build("2+2", aliases, engines).card)
    }
}
