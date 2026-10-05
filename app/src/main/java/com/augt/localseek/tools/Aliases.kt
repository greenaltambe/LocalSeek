package com.augt.localseek.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A prefix trigger. [target] is [CALCULATOR] or the id of a [WebEngine].
 * Example: trigger "g" + target "google" turns "g kotlin flow" into a Google search for "kotlin flow".
 */
data class Alias(val trigger: String, val target: String) {
    companion object {
        const val CALCULATOR = "calc"

        /** Scoped targets: restrict the local search to one kind of result instead of opening a web engine. */
        const val SCOPE_APPS = "scope_apps"
        const val SCOPE_CONTACTS = "scope_contacts"
        const val SCOPE_FILES = "scope_files"
        const val SCOPE_IMAGES = "scope_images"
        const val SCOPE_SETTINGS = "scope_settings"

        val SCOPE_TARGETS = listOf(SCOPE_APPS, SCOPE_CONTACTS, SCOPE_FILES, SCOPE_IMAGES, SCOPE_SETTINGS)
    }

    val isScope: Boolean get() = target in SCOPE_TARGETS
}

data class AliasMatch(val alias: Alias, val argument: String)

object Aliases {

    val DEFAULTS: List<Alias> = listOf(
        Alias("g", "google"),
        Alias("ddg", "duckduckgo"),
        Alias("yt", "youtube"),
        Alias("w", "wikipedia"),
        Alias("=", Alias.CALCULATOR)
    ) + scopedDefaults()

    /**
     * Default scoped prefixes in colon form: `a:` apps, `c:` contacts, `f:` files, `i:` images, `s:` device settings.
     * The colon forms trigger only when the colon directly follows the letter, so ordinary text such as "a note"
     * never turns into a chip.
     */
    fun scopedDefaults(): List<Alias> = listOf(
        Alias("a:", Alias.SCOPE_APPS),
        Alias("c:", Alias.SCOPE_CONTACTS),
        Alias("f:", Alias.SCOPE_FILES),
        Alias("i:", Alias.SCOPE_IMAGES),
        Alias("s:", Alias.SCOPE_SETTINGS)
    )

    /** The first (space-triggered) generation of scoped defaults, kept only to migrate them. */
    private fun legacyScopedDefaults(): List<Alias> = listOf(
        Alias("a", Alias.SCOPE_APPS),
        Alias("c", Alias.SCOPE_CONTACTS),
        Alias("f", Alias.SCOPE_FILES),
        Alias("i", Alias.SCOPE_IMAGES),
        Alias("s", Alias.SCOPE_SETTINGS)
    )

    /**
     * Replaces entries that are exactly a legacy default (`a` -> apps, ...) with the colon form, in place. Entries the
     * user edited (a different target, or a different trigger) are left alone, and a colon trigger that is already
     * taken is not overwritten. Returns the new list and how many entries were replaced.
     */
    fun migrateLegacyScopedDefaults(existing: List<Alias>): Pair<List<Alias>, Int> {
        val legacy = legacyScopedDefaults().associateBy { it.trigger }
        val colon = scopedDefaults().associateBy { it.target }
        var replaced = 0
        val result = existing.map { alias ->
            val old = legacy[alias.trigger]
            val new = old?.let { colon[it.target] }
            if (old != null && old == alias && new != null && existing.none { it.trigger.equals(new.trigger, ignoreCase = true) }) {
                replaced++
                new
            } else alias
        }
        return result to replaced
    }

    /**
     * Adds the scoped defaults to an existing (possibly user-edited) list without overwriting anything:
     * a default whose trigger is already taken is skipped and its trigger is returned in `skipped`.
     */
    fun withScopedDefaults(existing: List<Alias>): Pair<List<Alias>, List<String>> {
        val result = existing.toMutableList()
        val skipped = mutableListOf<String>()
        for (d in scopedDefaults()) {
            if (existing.any { it.trigger.equals(d.trigger, ignoreCase = true) } ||
                existing.any { it.target == d.target }
            ) skipped += d.trigger else result += d
        }
        return result to skipped
    }

    /**
     * Prefix detection for the search-bar chip. Letter triggers need "<trigger><whitespace><rest>" ("g weather"), so "g"
     * alone or "gadget" never turns into a chip. Triggers ending in a colon ("a:") match as soon as the colon directly
     * follows the trigger, with or without a space after it. The longest trigger wins. [rest] may be empty.
     */
    fun matchPrefixChip(text: String, aliases: List<Alias>): AliasMatch? {
        val q = text.trimStart()
        for (alias in aliases.sortedByDescending { it.trigger.length }) {
            val t = alias.trigger
            if (t.isEmpty() || !q.startsWith(t, ignoreCase = true)) continue
            val rest = q.substring(t.length)
            val colonForm = t.endsWith(":")
            if (!colonForm && (rest.isEmpty() || !rest.first().isWhitespace())) continue
            return AliasMatch(alias, rest.trimStart())
        }
        return null
    }

    /**
     * Matches "<trigger> <argument>". Triggers ending in a letter or digit need whitespace before the argument
     * ("g foo"); symbol triggers such as "=" also work without it ("=2+2"). The first matching alias wins and the
     * longest trigger is tried first, so "ddg" is never shadowed by "d".
     */
    fun match(query: String, aliases: List<Alias>): AliasMatch? {
        val q = query.trimStart()
        for (alias in aliases.sortedByDescending { it.trigger.length }) {
            val t = alias.trigger
            if (t.isEmpty() || !q.startsWith(t, ignoreCase = true)) continue
            val rest = q.substring(t.length)
            val symbolTrigger = !t.last().isLetterOrDigit()
            if (!symbolTrigger && (rest.isEmpty() || !rest.first().isWhitespace())) continue
            val arg = rest.trim()
            if (arg.isNotEmpty()) return AliasMatch(alias, arg)
        }
        return null
    }

    /** Triggers must be short, contain no whitespace, and be unique (case-insensitive). */
    fun isValidTrigger(trigger: String, existing: List<Alias>): Boolean {
        val t = trigger.trim()
        return t.isNotEmpty() && t.length <= 8 && t.none { it.isWhitespace() } &&
            existing.none { it.trigger.equals(t, ignoreCase = true) }
    }

    fun toJson(aliases: List<Alias>): String {
        val arr = JSONArray()
        aliases.forEach { arr.put(JSONObject().put("trigger", it.trigger).put("target", it.target)) }
        return arr.toString()
    }

    /** Lenient decode: invalid or duplicate entries are dropped. Returns null if [json] is not a JSON array. */
    fun fromJson(json: String): List<Alias>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val trigger = o.optString("trigger").trim(); val target = o.optString("target").trim()
            if (trigger.isEmpty() || trigger.length > 8 || trigger.any { it.isWhitespace() } || target.isEmpty()) null
            else Alias(trigger, target)
        }.distinctBy { it.trigger.lowercase() }
    } catch (_: JSONException) {
        null
    }
}
