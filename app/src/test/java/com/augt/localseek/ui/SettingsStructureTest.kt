package com.augt.localseek.ui

import com.augt.localseek.AppRoute
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.IndexNotificationMode
import com.augt.localseek.tools.SettingsBackup
import com.augt.localseek.tools.UiPrefs
import com.augt.localseek.tools.WebButtonStyle
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.tools.WebOpenMode
import com.augt.localseek.ui.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStructureTest {

    @Test
    fun `reranking is off by default and no longer claims a quality gain`() {
        assertFalse(AppSettings().enableReranking)
    }

    @Test
    fun `other existing defaults are unchanged`() {
        val d = AppSettings()
        assertTrue(d.enableDenseRetrieval)
        assertTrue(d.enableQueryExpansion)
        assertEquals(20, d.maxResults)
        assertTrue(d.batteryAwareMode)
        assertEquals(150, d.chunkSize)
        assertEquals(40, d.chunkOverlap)
        assertFalse(d.autoReindex)
        assertFalse(d.showDebugInfo)
    }

    @Test
    fun `new presentation preferences default as specified`() {
        val p = UiPrefs()
        assertTrue(p.mascot)
        assertTrue(p.rememberRecents)
        assertEquals(WebButtonStyle.ICON_AND_TEXT, p.webButtonStyle)
        assertEquals(WebOpenMode.IN_APP_TAB, p.webOpenMode)
        assertEquals(IndexNotificationMode.PROGRESS_AND_DONE, p.notificationMode)
        assertFalse(p.developerUnlocked)
        assertFalse(p.notificationPermissionAsked)
    }

    @Test
    fun `unknown stored enum names fall back to the default`() {
        assertEquals(WebOpenMode.IN_APP_TAB, UiPrefs.parse(WebOpenMode.values(), "GONE", WebOpenMode.IN_APP_TAB))
        assertEquals(WebOpenMode.DEFAULT_BROWSER, UiPrefs.parse(WebOpenMode.values(), "DEFAULT_BROWSER", WebOpenMode.IN_APP_TAB))
        assertEquals(WebButtonStyle.TEXT_ONLY, UiPrefs.parse(WebButtonStyle.values(), "TEXT_ONLY", WebButtonStyle.ICON_AND_TEXT))
    }

    @Test
    fun `back leads up the settings tree`() {
        assertEquals(AppRoute.SEARCH, AppRoute.SETTINGS.parent)
        assertEquals(AppRoute.SETTINGS, AppRoute.SETTINGS_WEB.parent)
        assertEquals(AppRoute.SETTINGS, AppRoute.ABOUT.parent)
        assertEquals(AppRoute.SETTINGS_DEVELOPER, AppRoute.PERFORMANCE.parent)
        assertEquals(AppRoute.SETTINGS_DEVELOPER, AppRoute.QRELS_LIST.parent)
        assertEquals(AppRoute.QRELS_LIST, AppRoute.QRELS_LABELING.parent)
    }

    @Test
    fun `route depth grows with every level and every route reaches search`() {
        assertEquals(0, AppRoute.SEARCH.depth)
        assertEquals(1, AppRoute.SETTINGS.depth)
        assertEquals(2, AppRoute.SETTINGS_INDEXING.depth)
        assertEquals(4, AppRoute.QRELS_LABELING.depth)
        AppRoute.values().forEach { start ->
            var r = start
            var steps = 0
            while (r != AppRoute.SEARCH && steps < 10) { r = r.parent; steps++ }
            assertEquals(start.name, AppRoute.SEARCH, r)
        }
    }

    @Test
    fun `backup import keeps scoped prefixes and engine icons`() {
        val engines = listOf(WebEngine("custom_maps", "Maps", "https://m.test/?q=%s", "map"))
        val aliases = listOf(Alias("a", Alias.SCOPE_APPS), Alias("m", "custom_maps"), Alias("zz", "deleted_engine"))
        val (kept, dropped) = SettingsBackup.pruneDeadAliases(aliases, engines)
        assertEquals(listOf("a", "m"), kept.map { it.trigger })
        assertEquals(1, dropped)
    }
}
