package com.augt.localseek.tools

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolsDataStore by preferencesDataStore(name = "tools_settings")

/**
 * DataStore-backed storage for the tools layer (aliases, web engines, pins and later theme).
 * Values are stored as JSON strings; an absent or corrupt value falls back to the defaults.
 * This is deliberately separate from the Room index and from the retrieval settings.
 */
class ToolsRepository(private val context: Context) {

    val aliases: Flow<List<Alias>> = context.toolsDataStore.data.map { pref ->
        pref[Keys.ALIASES]?.let { Aliases.fromJson(it) } ?: Aliases.DEFAULTS
    }

    val engines: Flow<List<WebEngine>> = context.toolsDataStore.data.map { pref ->
        pref[Keys.ENGINES]?.let { WebEngines.fromJson(it) }?.takeIf { it.isNotEmpty() } ?: WebEngines.DEFAULTS
    }

    val pins: Flow<List<Pin>> = context.toolsDataStore.data.map { pref ->
        pref[Keys.PINS]?.let { Pins.fromJson(it) } ?: emptyList()
    }

    val theme: Flow<ThemeSettings> = context.toolsDataStore.data.map { pref ->
        pref[Keys.THEME]?.let { ThemeSettings.fromJson(it) } ?: ThemeSettings()
    }

    /** Bottom-anchored search bar. Off by default. */
    val oneHandedMode: Flow<Boolean> = context.toolsDataStore.data.map { pref ->
        pref[Keys.ONE_HANDED] ?: false
    }

    suspend fun setOneHandedMode(enabled: Boolean) {
        context.toolsDataStore.edit { it[Keys.ONE_HANDED] = enabled }
    }

    /** True once the first-run onboarding has been finished or skipped. Not part of the settings backup. */
    val onboardingCompleted: Flow<Boolean> = context.toolsDataStore.data.map { pref ->
        pref[Keys.ONBOARDING_DONE] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.toolsDataStore.edit { it[Keys.ONBOARDING_DONE] = completed }
    }

    val uiPrefs: Flow<UiPrefs> = context.toolsDataStore.data.map { pref ->
        UiPrefs(
            mascot = pref[Keys.MASCOT] ?: true,
            rememberRecents = pref[Keys.REMEMBER_RECENTS] ?: true,
            webButtonStyle = UiPrefs.parse(WebButtonStyle.values(), pref[Keys.WEB_STYLE], WebButtonStyle.ICON_AND_TEXT),
            webOpenMode = UiPrefs.parse(WebOpenMode.values(), pref[Keys.WEB_OPEN], WebOpenMode.IN_APP_TAB),
            notificationMode = UiPrefs.parse(
                IndexNotificationMode.values(), pref[Keys.NOTIF_MODE], IndexNotificationMode.PROGRESS_AND_DONE
            ),
            developerUnlocked = pref[Keys.DEV_UNLOCKED] ?: false,
            notificationPermissionAsked = pref[Keys.NOTIF_PERMISSION_ASKED] ?: false
        )
    }

    suspend fun updateUiPrefs(transform: UiPrefs.() -> UiPrefs) {
        context.toolsDataStore.edit { pref ->
            val updated = pref.readUiPrefs().transform()
            pref[Keys.MASCOT] = updated.mascot
            pref[Keys.REMEMBER_RECENTS] = updated.rememberRecents
            pref[Keys.WEB_STYLE] = updated.webButtonStyle.name
            pref[Keys.WEB_OPEN] = updated.webOpenMode.name
            pref[Keys.NOTIF_MODE] = updated.notificationMode.name
            pref[Keys.DEV_UNLOCKED] = updated.developerUnlocked
            pref[Keys.NOTIF_PERMISSION_ASKED] = updated.notificationPermissionAsked
        }
    }

    private fun Preferences.readUiPrefs() = UiPrefs(
        mascot = this[Keys.MASCOT] ?: true,
        rememberRecents = this[Keys.REMEMBER_RECENTS] ?: true,
        webButtonStyle = UiPrefs.parse(WebButtonStyle.values(), this[Keys.WEB_STYLE], WebButtonStyle.ICON_AND_TEXT),
        webOpenMode = UiPrefs.parse(WebOpenMode.values(), this[Keys.WEB_OPEN], WebOpenMode.IN_APP_TAB),
        notificationMode = UiPrefs.parse(
            IndexNotificationMode.values(), this[Keys.NOTIF_MODE], IndexNotificationMode.PROGRESS_AND_DONE
        ),
        developerUnlocked = this[Keys.DEV_UNLOCKED] ?: false,
        notificationPermissionAsked = this[Keys.NOTIF_PERMISSION_ASKED] ?: false
    )

    /** Recent queries, newest first. Private to the app: not part of [snapshot] and never exported. */
    val recentSearches: Flow<List<String>> = context.toolsDataStore.data.map { pref ->
        pref[Keys.RECENTS]?.let { RecentSearches.fromJson(it) } ?: emptyList()
    }

    suspend fun addRecentSearch(query: String) {
        context.toolsDataStore.edit { pref ->
            if (pref[Keys.REMEMBER_RECENTS] == false) return@edit
            val current = pref[Keys.RECENTS]?.let { RecentSearches.fromJson(it) } ?: emptyList()
            pref[Keys.RECENTS] = RecentSearches.toJson(RecentSearches.add(current, query))
        }
    }

    suspend fun clearRecentSearches() {
        context.toolsDataStore.edit { it.remove(Keys.RECENTS) }
    }

    /**
     * One-time: adds the scoped prefixes (a, c, f, i, s) to a user's stored prefix list without overwriting any
     * existing prefix. Fresh installs already get them through [Aliases.DEFAULTS]. Returns skipped triggers.
     */
    suspend fun migrateScopedPrefixes(): List<String> {
        var skipped: List<String> = emptyList()
        context.toolsDataStore.edit { pref ->
            if (pref[Keys.SCOPED_MIGRATED] == true) return@edit
            pref[Keys.SCOPED_MIGRATED] = true
            val stored = pref[Keys.ALIASES]?.let { Aliases.fromJson(it) } ?: return@edit
            val (merged, skippedNow) = Aliases.withScopedDefaults(stored)
            skipped = skippedNow
            pref[Keys.ALIASES] = Aliases.toJson(merged)
        }
        return skipped
    }

    /**
     * One-time: swaps the first-generation scoped prefixes (a, c, f, i, s) for the colon forms (a:, c:, ...), only where an
     * entry is exactly the old default. Edited or user-made prefixes are untouched.
     */
    suspend fun migrateColonPrefixes() {
        context.toolsDataStore.edit { pref ->
            if (pref[Keys.COLON_MIGRATED] == true) return@edit
            pref[Keys.COLON_MIGRATED] = true
            val stored = pref[Keys.ALIASES]?.let { Aliases.fromJson(it) } ?: return@edit
            val (migrated, replaced) = Aliases.migrateLegacyScopedDefaults(stored)
            if (replaced > 0) pref[Keys.ALIASES] = Aliases.toJson(migrated)
        }
    }

    suspend fun setTheme(theme: ThemeSettings) {
        context.toolsDataStore.edit { it[Keys.THEME] = ThemeSettings.toJson(theme) }
    }

    suspend fun setAliases(aliases: List<Alias>) {
        context.toolsDataStore.edit { it[Keys.ALIASES] = Aliases.toJson(aliases) }
    }

    suspend fun setEngines(engines: List<WebEngine>) {
        context.toolsDataStore.edit { it[Keys.ENGINES] = WebEngines.toJson(engines) }
    }

    suspend fun setPins(pins: List<Pin>) {
        context.toolsDataStore.edit { it[Keys.PINS] = Pins.toJson(pins) }
    }

    /** Atomically toggles one pin so rapid taps cannot lose updates. */
    suspend fun togglePin(pin: Pin) {
        context.toolsDataStore.edit { pref ->
            val current = pref[Keys.PINS]?.let { Pins.fromJson(it) } ?: emptyList()
            pref[Keys.PINS] = Pins.toJson(Pins.toggle(current, pin))
        }
    }

    suspend fun snapshot(): SettingsSnapshot =
        SettingsSnapshot(aliases.first(), engines.first(), pins.first(), theme.first())

    /**
     * Applies an imported snapshot in one DataStore transaction. Sections that are null are left untouched.
     * Aliases pointing at engines that will not exist afterwards are dropped; returns warnings for the UI.
     */
    suspend fun apply(snapshot: SettingsSnapshot): List<String> {
        val warnings = mutableListOf<String>()
        context.toolsDataStore.edit { pref ->
            val currentEngines = pref[Keys.ENGINES]?.let { WebEngines.fromJson(it) }?.takeIf { it.isNotEmpty() }
                ?: WebEngines.DEFAULTS
            val currentAliases = pref[Keys.ALIASES]?.let { Aliases.fromJson(it) } ?: Aliases.DEFAULTS
            val engines = snapshot.engines ?: currentEngines
            val aliasesIn = snapshot.aliases ?: if (snapshot.engines != null) currentAliases else null
            snapshot.engines?.let { pref[Keys.ENGINES] = WebEngines.toJson(it) }
            aliasesIn?.let {
                val (kept, dropped) = SettingsBackup.pruneDeadAliases(it, engines)
                if (dropped > 0) warnings += "$dropped alias(es) pointing at missing engines were dropped"
                pref[Keys.ALIASES] = Aliases.toJson(kept)
            }
            snapshot.pins?.let { pref[Keys.PINS] = Pins.toJson(it) }
            snapshot.theme?.let { pref[Keys.THEME] = ThemeSettings.toJson(it) }
        }
        return warnings
    }

    suspend fun resetShortcuts() {
        context.toolsDataStore.edit {
            it.remove(Keys.ALIASES)
            it.remove(Keys.ENGINES)
        }
    }

    private object Keys {
        val ALIASES = stringPreferencesKey("aliases_json")
        val ENGINES = stringPreferencesKey("engines_json")
        val PINS = stringPreferencesKey("pins_json")
        val THEME = stringPreferencesKey("theme_json")
        val ONE_HANDED = booleanPreferencesKey("one_handed_mode")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val MASCOT = booleanPreferencesKey("ui_mascot")
        val REMEMBER_RECENTS = booleanPreferencesKey("ui_remember_recents")
        val WEB_STYLE = stringPreferencesKey("ui_web_button_style")
        val WEB_OPEN = stringPreferencesKey("ui_web_open_mode")
        val NOTIF_MODE = stringPreferencesKey("ui_notification_mode")
        val DEV_UNLOCKED = booleanPreferencesKey("ui_developer_unlocked")
        val NOTIF_PERMISSION_ASKED = booleanPreferencesKey("ui_notification_permission_asked")
        val RECENTS = stringPreferencesKey("recent_searches_json")
        val SCOPED_MIGRATED = booleanPreferencesKey("scoped_prefixes_migrated")
        val COLON_MIGRATED = booleanPreferencesKey("scoped_prefixes_colon_migrated")
    }
}
