package com.augt.localseek.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** A web search engine: [urlTemplate] contains one or more `%s` placeholders for the URL-encoded query. */
data class WebEngine(
    val id: String,
    val name: String,
    val urlTemplate: String,
    /** Key into [EngineIcons]; null means "use the default icon for this engine id, else a letter tile". */
    val iconKey: String? = null
) {
    /** The icon key actually shown: the stored one, else the built-in default for known engines. */
    val effectiveIconKey: String get() = iconKey ?: EngineIcons.defaultFor(id)
}

/** A ready-to-open search URL for one engine. */
data class WebAction(val engineId: String, val engineName: String, val url: String, val iconKey: String = EngineIcons.LETTER)

object WebEngines {

    val DEFAULTS: List<WebEngine> = listOf(
        WebEngine("google", "Google", "https://www.google.com/search?q=%s"),
        WebEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        WebEngine("youtube", "YouTube", "https://www.youtube.com/results?search_query=%s"),
        WebEngine("wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s")
    )

    /** Percent-encodes [query] for use inside a URL (spaces become %20, not '+'). */
    fun encodeQuery(query: String): String =
        URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    /** Expands every `%s` in [template] with the encoded query. Uses a literal replace, so `$` and `\` in the query are safe. */
    fun expand(template: String, query: String): String =
        template.replace("%s", encodeQuery(query))

    /** Only http/https templates that contain `%s` are accepted; this keeps intent:/file:/javascript: URLs out. */
    fun isValidTemplate(template: String): Boolean {
        val t = template.trim()
        if (!t.contains("%s") || t.any { it.isWhitespace() }) return false
        val lower = t.lowercase()
        if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false
        val host = lower.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        return host.isNotEmpty() && !host.contains("%s")
    }

    fun actions(engines: List<WebEngine>, query: String): List<WebAction> {
        if (query.isBlank()) return emptyList()
        return engines.map { WebAction(it.id, it.name, expand(it.urlTemplate, query), it.effectiveIconKey) }
    }

    /** Builds a custom engine with a generated id; returns null when name is blank or the template is invalid. */
    fun custom(name: String, template: String, existing: List<WebEngine>, iconKey: String? = null): WebEngine? {
        val n = name.trim()
        if (n.isEmpty() || !isValidTemplate(template)) return null
        val base = "custom_" + n.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "engine" }
        var id = base
        var i = 2
        while (existing.any { it.id == id }) id = "${base}_${i++}"
        return WebEngine(id, n, template.trim(), iconKey?.takeIf { it in EngineIcons.KEYS })
    }

    fun toJson(engines: List<WebEngine>): String {
        val arr = JSONArray()
        engines.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("template", it.urlTemplate)
            it.iconKey?.let { key -> o.put("icon", key) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Lenient decode: invalid entries are dropped. Returns null if [json] is not a JSON array at all. */
    fun fromJson(json: String): List<WebEngine>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id"); val name = o.optString("name"); val tpl = o.optString("template")
            val icon = o.optString("icon").takeIf { it in EngineIcons.KEYS }
            if (id.isBlank() || name.isBlank() || !isValidTemplate(tpl)) null else WebEngine(id, name, tpl, icon)
        }.distinctBy { it.id }
    } catch (_: JSONException) {
        null
    }
}
