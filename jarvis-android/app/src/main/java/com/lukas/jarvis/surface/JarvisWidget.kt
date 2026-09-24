package com.lukas.jarvis.surface

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.R
import java.util.Calendar

/**
 * A bar on the home screen: the reactor, a greeting, and buttons to talk, type
 * or show Jarvis something. The greeting follows the time of day and the name
 * the assistant has been given.
 */
class JarvisWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val settings = runCatching {
            (context.applicationContext as JarvisApp).container.settings.current
        }.getOrNull()
        val name = settings?.assistantName?.ifBlank { null } ?: "Jarvis"
        val user = settings?.userName?.takeIf { it.isNotBlank() }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            in 18..22 -> "Good evening"
            else -> "Still up"
        }

        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_jarvis).apply {
                setTextViewText(R.id.widget_title, if (user != null) "$part, $user" else "Ask $name anything")
                setTextViewText(R.id.widget_subtitle, "Tap to talk to $name")
                setOnClickPendingIntent(R.id.widget_core, Entry.pending(context, Entry.TALK, 11))
                setOnClickPendingIntent(R.id.widget_talk_area, Entry.pending(context, Entry.TALK, 12))
                setOnClickPendingIntent(R.id.widget_talk, Entry.pending(context, Entry.TALK, 13))
                setOnClickPendingIntent(R.id.widget_type, Entry.pending(context, Entry.TYPE, 14))
                setOnClickPendingIntent(R.id.widget_scan, Entry.pending(context, Entry.SCAN, 15))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
