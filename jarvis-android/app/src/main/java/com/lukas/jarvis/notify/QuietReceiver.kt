package com.lukas.jarvis.notify

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Ends a Do Not Disturb that was asked for "for an hour".
 *
 * Android has no public way to set a timed one, so the end is a one-off alarm
 * of ours. Only a quiet Jarvis started is ended: if the user has since changed
 * it by hand, what they chose stands.
 */
class QuietReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val wanted = intent.getIntExtra(EXTRA_FILTER, -1)
        if (wanted != -1 && manager.currentInterruptionFilter != wanted) return
        runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
    }

    companion object {
        private const val EXTRA_FILTER = "filter"

        /** Arms the end [minutes] from now, or cancels a pending one when null. */
        fun schedule(context: Context, minutes: Int?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val filter = context.getSystemService(NotificationManager::class.java)
                ?.currentInterruptionFilter ?: -1
            val intent = Intent(context, QuietReceiver::class.java).putExtra(EXTRA_FILTER, filter)
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarms.cancel(pending)
            if (minutes == null || minutes <= 0) return
            val at = System.currentTimeMillis() + minutes * 60_000L
            // Inexact is fine: a quiet hour that ends a minute late harms nobody,
            // and it needs no exact-alarm permission.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }

        private const val REQUEST = 7301
    }
}
