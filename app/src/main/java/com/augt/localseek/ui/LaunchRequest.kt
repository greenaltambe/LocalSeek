package com.augt.localseek.ui

/**
 * What an incoming intent asks the search screen to do: a launcher shortcut ("Search", "Search images") or text shared
 * from another app. Pure so it can be unit tested; the activity passes in the plain intent fields.
 */
sealed interface LaunchRequest {
    data object None : LaunchRequest

    /** Focus on one kind of result (launcher shortcut). */
    data class Scope(val scope: TypeScope) : LaunchRequest

    /** Prefill the search with shared text. */
    data class Search(val text: String) : LaunchRequest

    companion object {
        const val ACTION_SEARCH = "com.augt.localseek.action.SEARCH"
        const val EXTRA_SCOPE = "scope"
        const val MAX_SHARED_LENGTH = 200

        fun fromIntent(action: String?, mimeType: String?, sharedText: String?, scopeExtra: String?): LaunchRequest = when (action) {
            ACTION_SEARCH -> when (scopeExtra?.lowercase()) {
                "images" -> Scope(TypeScope.IMAGES)
                "files" -> Scope(TypeScope.FILES)
                "apps" -> Scope(TypeScope.APPS)
                "contacts" -> Scope(TypeScope.CONTACTS)
                else -> Scope(TypeScope.ALL)
            }
            "android.intent.action.SEND" -> {
                if (mimeType?.startsWith("text/") != true) None
                else {
                    // single line, bounded length: shared text can be a whole article or contain private notes
                    val text = sharedText.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_SHARED_LENGTH).trim()
                    if (text.isEmpty()) None else Search(text)
                }
            }
            else -> None
        }
    }
}
