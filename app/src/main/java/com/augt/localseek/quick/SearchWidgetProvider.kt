package com.augt.localseek.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.augt.localseek.MainActivity
import com.augt.localseek.R

/** Home-screen search bar widget. It only opens the app: no data is shown and nothing is fetched. */
class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_search)
            views.setOnClickPendingIntent(R.id.widget_root, launch)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
