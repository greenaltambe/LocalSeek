package com.augt.localseek.tools

import android.provider.Settings

/**
 * One entry of the static device-settings index. [sinceApi] is the API level that introduced [action];
 * every entry must be <= 26 (the app's minSdk) so it works on all supported devices.
 */
data class SettingsEntry(
    val title: String,
    val action: String,
    val synonyms: List<String>,
    val sinceApi: Int = 1,
    /** The launcher adds this app's package as EXTRA_APP_PACKAGE (needed by ACTION_APP_NOTIFICATION_SETTINGS). */
    val usesOwnPackage: Boolean = false
)

object SettingsIndex {

    const val MIN_SDK = 26

    val ENTRIES: List<SettingsEntry> = listOf(
        SettingsEntry("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wireless", "network", "internet", "hotspot")),
        SettingsEntry("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("pair", "headphones", "earbuds", "wireless")),
        SettingsEntry("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("flight", "aeroplane", "offline"), 3),
        SettingsEntry("Mobile network", Settings.ACTION_DATA_ROAMING_SETTINGS, listOf("cellular", "data", "roaming", "sim", "mobile data")),
        SettingsEntry("NFC", Settings.ACTION_NFC_SETTINGS, listOf("tap to pay", "contactless"), 16),
        SettingsEntry("Display", Settings.ACTION_DISPLAY_SETTINGS, listOf("brightness", "screen", "font size", "timeout", "rotation", "dark mode", "theme")),
        SettingsEntry("Night light", Settings.ACTION_NIGHT_DISPLAY_SETTINGS, listOf("blue light", "night mode", "eye comfort", "warm"), 26),
        SettingsEntry("Sound", Settings.ACTION_SOUND_SETTINGS, listOf("volume", "ringtone", "vibration", "silent", "audio")),
        SettingsEntry("Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS, listOf("battery", "power saving", "low power"), 22),
        SettingsEntry("Battery optimization", Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, listOf("battery", "background", "doze", "restrict"), 23),
        SettingsEntry("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("space", "memory", "free up", "files"), 3),
        SettingsEntry("Apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("applications", "permissions", "uninstall")),
        SettingsEntry("All apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, listOf("installed apps", "force stop", "clear cache", "uninstall"), 9),
        SettingsEntry("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, listOf("default browser", "launcher", "assistant"), 24),
        SettingsEntry("LocalSeek notifications", Settings.ACTION_APP_NOTIFICATION_SETTINGS, listOf("notifications", "alerts", "notification"), 26, usesOwnPackage = true),
        SettingsEntry("Notification access", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, listOf("notification listener", "notifications"), 22),
        SettingsEntry("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("talkback", "screen reader", "magnification", "display size")),
        SettingsEntry("Date & time", Settings.ACTION_DATE_SETTINGS, listOf("clock", "timezone", "time zone", "24 hour")),
        SettingsEntry("Language", Settings.ACTION_LOCALE_SETTINGS, listOf("languages", "locale", "region", "translate")),
        SettingsEntry("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, listOf("input method", "typing", "ime", "keyboards")),
        SettingsEntry("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("gps", "maps", "location services")),
        SettingsEntry("Security", Settings.ACTION_SECURITY_SETTINGS, listOf("lock screen", "fingerprint", "pin", "password", "screen lock")),
        SettingsEntry("Privacy", Settings.ACTION_PRIVACY_SETTINGS, listOf("permissions", "backup", "reset"), 5),
        SettingsEntry("Accounts", Settings.ACTION_ADD_ACCOUNT, listOf("add account", "sign in", "google account", "email"), 5),
        SettingsEntry("Sync", Settings.ACTION_SYNC_SETTINGS, listOf("auto sync", "synchronize", "accounts"), 5),
        SettingsEntry("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("usb debugging", "adb", "developer")),
        SettingsEntry("Usage access", Settings.ACTION_USAGE_ACCESS_SETTINGS, listOf("app usage", "screen time"), 21),
        SettingsEntry("Display over other apps", Settings.ACTION_MANAGE_OVERLAY_PERMISSION, listOf("overlay", "draw over", "system alert window"), 23),
        SettingsEntry("Cast", Settings.ACTION_CAST_SETTINGS, listOf("screen mirroring", "chromecast", "miracast"), 21),
        SettingsEntry("Home app", Settings.ACTION_HOME_SETTINGS, listOf("launcher", "home screen", "default launcher"), 21),
        SettingsEntry("Printing", Settings.ACTION_PRINT_SETTINGS, listOf("printer", "print service"), 19),
        SettingsEntry("Captions", Settings.ACTION_CAPTIONING_SETTINGS, listOf("subtitles", "closed captions"), 19),
        SettingsEntry("Screen saver", Settings.ACTION_DREAM_SETTINGS, listOf("daydream", "screensaver"), 18),
        SettingsEntry("Settings", Settings.ACTION_SETTINGS, listOf("system settings", "preferences", "options"))
    )
}

/** Simple offline token/prefix matcher over [SettingsIndex]. */
class SettingsSearch(private val entries: List<SettingsEntry> = SettingsIndex.ENTRIES) {

    private class Indexed(val entry: SettingsEntry, val titleTokens: List<String>, val synonymTokens: List<String>, val phrases: List<String>)

    private val indexed = entries.map { e ->
        Indexed(
            e,
            tokenize(e.title),
            e.synonyms.flatMap { tokenize(it) },
            (listOf(e.title) + e.synonyms).map { it.lowercase() }
        )
    }

    /**
     * All query tokens (2+ chars) must match. A token matches exactly (title 3, synonym 2) or as a prefix when it
     * has 3+ characters (title 2, synonym 1). A whole-phrase hit adds 3. Results are best-first, at most [limit].
     */
    fun search(query: String, limit: Int = 3): List<SettingsEntry> {
        val tokens = tokenize(query).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        val q = query.trim().lowercase()

        return indexed.mapNotNull { item ->
            var total = 0
            for (t in tokens) {
                val s = scoreToken(t, item)
                if (s == 0) return@mapNotNull null
                total += s
            }
            if (item.phrases.any { it == q }) total += 3
            item to total
        }
            .sortedByDescending { it.second } // stable: ties keep index order
            .take(limit)
            .map { it.first.entry }
    }

    private fun scoreToken(t: String, item: Indexed): Int {
        if (t in item.titleTokens) return 3
        if (t in item.synonymTokens) return 2
        if (t.length >= 3) {
            if (item.titleTokens.any { it.startsWith(t) }) return 2
            if (item.synonymTokens.any { it.startsWith(t) }) return 1
        }
        return 0
    }

    companion object {
        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }
}
