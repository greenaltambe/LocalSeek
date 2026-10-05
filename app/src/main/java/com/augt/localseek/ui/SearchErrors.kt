package com.augt.localseek.ui

/** User-facing text for a failed search: never empty, never a bare class name. */
fun searchFailureMessage(error: Throwable): String {
    val detail = error.message?.trim().orEmpty()
    return if (detail.isEmpty()) "The search could not be completed" else "The search could not be completed: $detail"
}
