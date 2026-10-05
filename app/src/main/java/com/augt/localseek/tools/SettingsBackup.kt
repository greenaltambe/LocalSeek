package com.augt.localseek.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The complete set of things a settings backup contains. There is deliberately nothing else here: no search
 * index, no documents, no contact data (contact pins are excluded), and no API keys or tokens.
 * A null section means "not in the file / leave the current value untouched" when importing.
 */
data class SettingsSnapshot(
    val aliases: List<Alias>? = null,
    val engines: List<WebEngine>? = null,
    val pins: List<Pin>? = null,
    val theme: ThemeSettings? = null
)

sealed class BackupParseResult {
    data class Ok(val snapshot: SettingsSnapshot, val warnings: List<String>) : BackupParseResult()
    data class Invalid(val reason: String) : BackupParseResult()
}

object SettingsBackup {

    const val FORMAT = "localseek-settings"
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 256 * 1024
    private val BOM = 0xFEFF.toChar().toString()
    const val MAX_ALIASES = 100
    const val MAX_ENGINES = 50

    /** The only top-level keys a backup file may contain. */
    val ALLOWED_KEYS = setOf("format", "schemaVersion", "aliases", "engines", "pins", "theme")

    fun export(snapshot: SettingsSnapshot): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
        snapshot.aliases?.let { root.put("aliases", JSONArray(Aliases.toJson(it))) }
        snapshot.engines?.let { root.put("engines", JSONArray(WebEngines.toJson(it))) }
        snapshot.pins?.let { pins ->
            val exportable = pins.filterNot { it.entityType.equals("CONTACT", ignoreCase = true) }
            root.put("pins", JSONArray(Pins.toJson(exportable)))
        }
        snapshot.theme?.let { root.put("theme", JSONObject(ThemeSettings.toJson(it))) }
        return root.toString(2)
    }

    /** Strictly validates structure and version; drops individually invalid entries and reports them as warnings. */
    fun parse(rawJson: String): BackupParseResult {
        // Some editors prepend a byte-order mark, which JSONObject rejects.
        val json = rawJson.removePrefix(BOM)
        if (json.length > MAX_BYTES) return BackupParseResult.Invalid("File is too large to be a settings backup")
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return BackupParseResult.Invalid("Not a valid JSON settings file")
        }
        if (root.optString("format") != FORMAT) return BackupParseResult.Invalid("Not a LocalSeek settings backup")
        val version = root.optInt("schemaVersion", -1)
        if (version < 1) return BackupParseResult.Invalid("Missing or invalid schema version")
        if (version > SCHEMA_VERSION) {
            return BackupParseResult.Invalid("Backup was made by a newer version of LocalSeek (schema $version)")
        }
        val unknown = root.keys().asSequence().filter { it !in ALLOWED_KEYS }.toList()
        if (unknown.isNotEmpty()) return BackupParseResult.Invalid("Unexpected fields: ${unknown.joinToString()}")

        val warnings = mutableListOf<String>()

        var engines: List<WebEngine>? = null
        if (root.has("engines")) {
            val arr = root.optJSONArray("engines") ?: return BackupParseResult.Invalid("'engines' must be a list")
            if (arr.length() > MAX_ENGINES) return BackupParseResult.Invalid("Too many engines")
            engines = WebEngines.fromJson(arr.toString()) ?: return BackupParseResult.Invalid("'engines' is invalid")
            if (engines.size < arr.length()) warnings += "${arr.length() - engines.size} invalid engine(s) skipped"
            if (engines.isEmpty()) return BackupParseResult.Invalid("'engines' contains no valid engine")
        }

        var aliases: List<Alias>? = null
        if (root.has("aliases")) {
            val arr = root.optJSONArray("aliases") ?: return BackupParseResult.Invalid("'aliases' must be a list")
            if (arr.length() > MAX_ALIASES) return BackupParseResult.Invalid("Too many aliases")
            val decoded = Aliases.fromJson(arr.toString()) ?: return BackupParseResult.Invalid("'aliases' is invalid")
            if (decoded.size < arr.length()) warnings += "${arr.length() - decoded.size} invalid alias(es) skipped"
            aliases = decoded
        }

        var pins: List<Pin>? = null
        if (root.has("pins")) {
            val arr = root.optJSONArray("pins") ?: return BackupParseResult.Invalid("'pins' must be a list")
            val decoded = Pins.fromJson(arr.toString()) ?: return BackupParseResult.Invalid("'pins' is invalid")
            val nonContacts = decoded.filterNot { it.entityType.equals("CONTACT", ignoreCase = true) }
            val dropped = (arr.length() - decoded.size) + (decoded.size - nonContacts.size)
            if (dropped > 0) warnings += "$dropped invalid or surplus pin(s) skipped"
            pins = nonContacts
        }

        var theme: ThemeSettings? = null
        if (root.has("theme")) {
            val obj = root.optJSONObject("theme") ?: return BackupParseResult.Invalid("'theme' must be an object")
            theme = ThemeSettings.fromJson(obj.toString()) ?: return BackupParseResult.Invalid("'theme' is invalid")
        }

        return BackupParseResult.Ok(SettingsSnapshot(aliases, engines, pins, theme), warnings)
    }

    /**
     * Drops aliases that point at an engine that will not exist after import ([resultingEngines]).
     * Returns the cleaned list plus how many were dropped.
     */
    fun pruneDeadAliases(aliases: List<Alias>, resultingEngines: List<WebEngine>): Pair<List<Alias>, Int> {
        val kept = aliases.filter { it.target == Alias.CALCULATOR || it.isScope || resultingEngines.any { e -> e.id == it.target } }
        return kept to (aliases.size - kept.size)
    }
}
