package com.augt.localseek.tools

/**
 * Built-in icon keys for search engines. No favicons and no brand logos: every key maps to a generic Material
 * icon (see ui/theme/EngineIconVectors.kt), and [LETTER] draws the engine's first letter in a tile.
 */
object EngineIcons {
    const val LETTER = "letter"

    /** The picker grid, in display order. Each key has an ImageVector in the UI layer. */
    val KEYS: List<String> = listOf(
        LETTER, "web", "video", "book", "map", "code", "shopping", "music", "news", "ai",
        "search", "image", "movie", "photo", "school", "science", "travel", "restaurant", "weather", "sports",
        "game", "health", "finance", "work", "mail", "chat", "download", "star", "home", "language", "shield"
    )

    /** Sensible icon for the default engines; anything else starts as a letter tile. */
    fun defaultFor(engineId: String): String = when (engineId) {
        "google" -> "search"
        "duckduckgo" -> "shield"
        "youtube" -> "video"
        "wikipedia" -> "book"
        else -> LETTER
    }
}
