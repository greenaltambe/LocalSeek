package com.augt.localseek.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.augt.localseek.tools.WebOpenMode

/**
 * Opens a web URL in a Custom Tab (default) or in the default browser, falling back to a plain ACTION_VIEW. LocalSeek
 * never performs the request itself (it has no INTERNET permission) and never uses a WebView; the user's browser does.
 */
object WebLauncher {

    fun open(context: Context, url: String, mode: WebOpenMode = WebOpenMode.IN_APP_TAB): Boolean {
        val uri = url.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val newTask = context !is Activity
        if (mode == WebOpenMode.IN_APP_TAB) try {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            if (newTask) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, uri)
            return true
        } catch (_: ActivityNotFoundException) {
            // fall through to ACTION_VIEW
        } catch (e: Exception) {
            Log.w("WebLauncher", "Custom Tab launch failed", e)
        }
        return try {
            val view = Intent(Intent.ACTION_VIEW, uri)
            if (newTask) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
