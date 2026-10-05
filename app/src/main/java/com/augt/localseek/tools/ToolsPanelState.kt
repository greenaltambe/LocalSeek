package com.augt.localseek.tools

/** Everything the tools layer wants to show above the search results for the current query. */
data class ToolsPanelState(
    val card: ToolCard? = null,
    /** Set when the query used an engine alias such as "g kotlin": the primary action for that query. */
    val aliasAction: WebAction? = null,
    /** Web search fallback bar: one action per configured engine. */
    val webActions: List<WebAction> = emptyList(),
    /** Device settings screens matching the query (offline index of Settings.ACTION_* intents). */
    val settingsMatches: List<SettingsEntry> = emptyList()
) {
    val isEmpty: Boolean
        get() = card == null && aliasAction == null && webActions.isEmpty() && settingsMatches.isEmpty()
}

/** Builds the [ToolsPanelState] for a query. Pure Kotlin; never touches the search pipeline. */
class ToolsPanelBuilder(
    private val resolver: ToolResolver = ToolResolver(),
    private val settingsSearch: SettingsSearch = SettingsSearch()
) {

    fun build(query: String, aliases: List<Alias>, engines: List<WebEngine>): ToolsPanelState {
        if (query.isBlank()) return ToolsPanelState()

        val match = Aliases.match(query, aliases)
        if (match != null) {
            if (match.alias.target == Alias.CALCULATOR) {
                return ToolsPanelState(card = resolver.resolve(match.argument, forceCalculator = true))
            }
            val engine = engines.firstOrNull { it.id == match.alias.target }
            if (engine != null) {
                val action = WebAction(engine.id, engine.name, WebEngines.expand(engine.urlTemplate, match.argument), engine.effectiveIconKey)
                return ToolsPanelState(aliasAction = action)
            }
            // Alias points at a deleted engine: fall through and treat the query as a normal search.
        }

        return ToolsPanelState(
            card = resolver.resolve(query),
            webActions = WebEngines.actions(engines, query),
            settingsMatches = settingsSearch.search(query)
        )
    }
}
