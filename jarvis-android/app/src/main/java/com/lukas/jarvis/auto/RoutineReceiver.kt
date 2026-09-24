package com.lukas.jarvis.auto

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R

/** A routine's time: offer it with one tap, then set tomorrow's alarm. */
class RoutineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Routines.ACTION_DUE) return
        val name = intent.getStringExtra(Routines.EXTRA_NAME) ?: return

        val open = PendingIntent.getActivity(
            context,
            name.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RUN_ROUTINE, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, Routines.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name.replaceFirstChar { it.uppercase() })
            .setContentText("Tap and Jarvis runs your routine")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(("routine-$name").hashCode(), notification)

        // Daily: the alarm is one-shot, so set the next one now.
        val routines = (context.applicationContext as? JarvisApp)?.container?.routines ?: return
        routines.find(name)?.let { routines.schedule(it) }
    }
}
