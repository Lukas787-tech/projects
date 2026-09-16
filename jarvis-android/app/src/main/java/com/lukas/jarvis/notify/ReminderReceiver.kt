package com.lukas.jarvis.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.Brain

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION_FIRE) return
        val taskId = intent.getLongExtra(Reminders.EXTRA_TASK_ID, -1L)
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE) ?: "Reminder"
        val notes = intent.getStringExtra(Reminders.EXTRA_NOTES)

        val open = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, Reminders.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(notes ?: "Tap to open Jarvis")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(taskId.toInt().coerceAtLeast(1), notification)

        // A repeating task rolls forward to its next slot instead of going quiet.
        if (taskId > 0) {
            val brain = Brain(context)
            val task = brain.getTask(taskId) ?: return
            val next = task.dueAt?.let { TimeUtil.nextOccurrence(it, task.repeatRule) } ?: return
            val rolled = task.copy(dueAt = next)
            brain.updateTask(rolled)
            Reminders(context).schedule(rolled)
        }
    }
}
