package com.lukas.jarvis.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
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
 *
 * When it ends, one notification says who wrote meanwhile ([QuietDigest]),
 * from the messages already in the shade; a tap asks Mochi to go through them.
 */
class QuietReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) return
        val wanted = intent.getIntExtra(EXTRA_FILTER, -1)
        if (wanted != -1 && manager.currentInterruptionFilter != wanted) return
        runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        val since = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getLong(KEY_SINCE, 0L)
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(KEY_SINCE).apply()
        if (since > 0L) QuietDigest.of(ReplyListener.waiting(), since)?.let { post(context, manager, it) }
    }

    private fun post(context: Context, manager: NotificationManager, digest: Digest) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "After a quiet time", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Who wrote while Do Not Disturb was on, when Mochi ends it" }
            )
        }
        // A tap asks for the messages, as if it had been typed; Mochi reads them and offers replies.
        val ask = Intent(context, com.lukas.jarvis.MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "What messages did I get?")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, REQUEST + 1, ask, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = digest.lines.joinToString("\n")
        // On the lock screen, only that someone wrote: the words stay behind the unlock.
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.lukas.jarvis.R.drawable.ic_notification)
            .setContentTitle(if (digest.people == 1) "A message while it was quiet" else "Messages while it was quiet")
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.lukas.jarvis.R.drawable.ic_notification)
            .setContentTitle(digest.title)
            .setContentText(digest.lines.firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
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
            val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            if (minutes == null || minutes <= 0) {
                store.edit().remove(KEY_SINCE).apply()
                return
            }
            // From now, what comes in counts for the digest at the end.
            store.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply()
            val at = System.currentTimeMillis() + minutes * 60_000L
            // Inexact is fine: a quiet hour that ends a minute late harms nobody,
            // and it needs no exact-alarm permission.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }

        private const val REQUEST = 7301
        private const val STORE = "jarvis_quiet"
        private const val KEY_SINCE = "since"
        private const val CHANNEL = "mochi_quiet"
        // Clear of the reminders', the place alerts' and the money notice's ids.
        private const val NOTIFICATION_ID = 68_301
    }
}
