package com.augt.localseek.tools

/** How the web-search buttons are drawn. */
enum class WebButtonStyle { ICON_AND_TEXT, ICON_ONLY, TEXT_ONLY }

/** How engine searches open: an in-app Custom Tab or the default browser. Never a WebView. */
enum class WebOpenMode { IN_APP_TAB, DEFAULT_BROWSER }

/** Which indexing notifications are shown. */
enum class IndexNotificationMode { PROGRESS_AND_DONE, PROGRESS_ONLY, MINIMAL }

/**
 * Presentation preferences kept in the tools DataStore. Defaults match the brief: mascot on, recent searches on,
 * icon + text web buttons, in-app tab, progress + done notifications.
 */
data class UiPrefs(
    val mascot: Boolean = true,
    val rememberRecents: Boolean = true,
    val webButtonStyle: WebButtonStyle = WebButtonStyle.ICON_AND_TEXT,
    val webOpenMode: WebOpenMode = WebOpenMode.IN_APP_TAB,
    val notificationMode: IndexNotificationMode = IndexNotificationMode.PROGRESS_AND_DONE,
    /** True once the 7-tap About unlock was used; Developer options are always visible in debug builds. */
    val developerUnlocked: Boolean = false,
    /** True once the notification permission prompt was shown (it is requested at most once unless "Enable" is tapped). */
    val notificationPermissionAsked: Boolean = false
) {
    companion object {
        fun <E : Enum<E>> parse(values: Array<E>, name: String?, default: E): E =
            values.firstOrNull { it.name == name } ?: default
    }
}
