package com.augt.localseek.tools

import org.json.JSONArray
import org.json.JSONException

/**
 * Recent search queries, kept only in the app's private settings store. Never logged, never exported
 * (SettingsBackup does not read them). Newest first, case-insensitive de-duplication, capped at [MAX].
 */
object RecentSearches {

    const val MAX = 10
    private const val MAX_LENGTH = 120

    /** Returns the list with [query] at the front; blank queries leave the list unchanged. */
    fun add(current: List<String>, query: String): List<String> {
        val q = query.trim().take(MAX_LENGTH)
        if (q.isEmpty()) return current
        return (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    fun remove(current: List<String>, query: String): List<String> =
        current.filterNot { it.equals(query, ignoreCase = true) }

    fun toJson(list: List<String>): String = JSONArray(list.take(MAX)).toString()

    /** Lenient decode: non-string and blank entries are dropped; null if [json] is not a JSON array. */
    fun fromJson(json: String): List<String>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
            .distinctBy { it.lowercase() }.take(MAX)
    } catch (_: JSONException) {
        null
    }
}
