package com.lukas.jarvis.brief

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.surface.Entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * The morning brief, written out as a notification at a time the user chose.
 *
 * The spoken brief waits for the app to be opened; this one comes to the
 * user. Everything in it is gathered the same way — one [Briefer] — so the
 * notification and the screen never describe different mornings. The alarm
 * is inexact on purpose: a brief two minutes late is still a brief, and an
 * inexact alarm needs no special permission.
 */
object BriefAlarm {

    const val CHANNEL = "brief"
    private const val REQUEST = 7411
    private const val NOTIFICATION_ID = 7411

    /** Sets tomorrow's (or today's, if still ahead) brief, or clears it for a blank time. */
    fun schedule(context: Context, time: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context)
        alarms.cancel(pending)
        val (hour, minute) = parse(time) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    }

    fun parse(time: String): Pair<Int, Int>? {
        val parts = time.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour to minute
    }

    internal fun post(context: Context, brief: DayBrief) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "How the day looks, at the time you chose" }
        )
        val open = PendingIntent.getActivity(
            context,
            REQUEST,
            Intent(context, MainActivity::class.java)
                .setAction(Entry.TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = brief.speak().removePrefix(brief.greeting).trimStart('.', ' ')
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(brief.greeting)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, BriefReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** The brief's alarm: gather the day, post it, set tomorrow's. */
class BriefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? JarvisApp)?.container ?: return
        val settings = container.settings.current
        if (settings.briefTime.isBlank()) return
        // Tomorrow's first, so a brief that fails today does not end the habit.
        BriefAlarm.schedule(context, settings.briefTime)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A receiver has well under a minute; a brief without the
                // weather is better than none.
                val brief = withTimeoutOrNull(25_000L) {
                    runCatching { container.briefer.build(settings) }.getOrNull()
                }
                if (brief != null) BriefAlarm.post(context, brief)
            } finally {
                pending.finish()
            }
        }
    }
}
