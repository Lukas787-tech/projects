package com.lukas.jarvis.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.core.TimeUtil

/**
 * A reminder going off, and the two buttons on it.
 *
 * "Done" ticks the task off and "Snooze" moves it ten minutes on, both without
 * opening the app — the same two things anyone does with a reminder, one tap
 * each. The work runs on the app's own database connection rather than a new
 * one, so a reminder firing while the assistant is writing does not find the
 * database locked.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(Reminders.EXTRA_TASK_ID, -1L)
        when (intent.action) {
            Reminders.ACTION_FIRE -> fire(context, intent, taskId)
            ACTION_DONE -> inBackground { done(context, taskId) }
            ACTION_SNOOZE -> inBackground { snooze(context, taskId) }
        }
    }

    private fun BroadcastReceiver.inBackground(work: () -> Unit) {
        val pending = goAsync()
        Thread {
            try {
                runCatching(work)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun fire(context: Context, intent: Intent, taskId: Long) {
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE) ?: "Reminder"
        val notes = intent.getStringExtra(Reminders.EXTRA_NOTES)

        val open = PendingIntent.getActivity(
            context,
            taskId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, Reminders.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(notes ?: "Tap to open Jarvis")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (taskId > 0) {
            builder.addAction(action(context, ACTION_DONE, taskId, "Done"))
            builder.addAction(action(context, ACTION_SNOOZE, taskId, "Snooze 10 min"))
        }

        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationId(taskId), builder.build())

        // With the app in front of them, the assistant says it, the way a
        // person sitting beside them would — in its own form of address.
        val app = context.applicationContext as? JarvisApp
        if (app != null && app.inForeground) {
            val settings = app.container.settings.current
            if (settings.speakReplies) {
                val address = com.lukas.jarvis.llm.Personas.address(settings)
                val lead = if (address.isBlank()) "A reminder" else "A reminder, $address"
                runCatching { app.container.speaker.speak("$lead: $title.") { } }
            }
        }

        // A repeating task rolls forward to its next slot instead of going quiet
        // — but not when this was the extra ring of a snooze, which the roll
        // already happened for.
        val snoozeRing = intent.data?.toString()?.endsWith("/once") == true
        if (taskId > 0 && !snoozeRing) {
            inBackground {
                val brain = brain(context)
                val task = brain.getTask(taskId) ?: return@inBackground
                val next = task.dueAt?.let { TimeUtil.nextOccurrence(it, task.repeatRule) }
                    ?: return@inBackground
                val rolled = task.copy(dueAt = next)
                brain.updateTask(rolled)
                Reminders(context).schedule(rolled)
            }
        }
    }

    private fun done(context: Context, taskId: Long) {
        dismiss(context, taskId)
        if (taskId <= 0) return
        val brain = brain(context)
        val task = brain.getTask(taskId) ?: return
        // A repeating task has already rolled to its next time; ticking this
        // one off must not stop the next.
        if (task.repeatRule != "none") return
        brain.updateTask(task.copy(done = true, completedAt = System.currentTimeMillis()))
        Reminders(context).cancel(taskId)
    }

    private fun snooze(context: Context, taskId: Long) {
        dismiss(context, taskId)
        if (taskId <= 0) return
        val brain = brain(context)
        val task = brain.getTask(taskId) ?: return
        val later = System.currentTimeMillis() + SNOOZE_MS
        // A repeating task keeps its rolled-forward slot; the snooze is a
        // one-off copy of the alarm rather than a move of the whole series.
        if (task.repeatRule != "none") {
            Reminders(context).scheduleOnce(task, later)
        } else {
            val moved = task.copy(dueAt = later, done = false)
            brain.updateTask(moved)
            Reminders(context).schedule(moved)
        }
    }

    private fun dismiss(context: Context, taskId: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(taskId))
    }

    private fun action(context: Context, action: String, taskId: Long, label: String): Notification.Action {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            data = android.net.Uri.parse("jarvis://task/$taskId/$action")
            putExtra(Reminders.EXTRA_TASK_ID, taskId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            (taskId.toInt() * 31) + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            label,
            pending
        ).build()
    }

    private fun brain(context: Context) = (context.applicationContext as JarvisApp).container.brain

    private fun notificationId(taskId: Long) = taskId.toInt().coerceAtLeast(1)

    companion object {
        const val ACTION_DONE = "com.lukas.jarvis.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.lukas.jarvis.REMINDER_SNOOZE"
        private const val SNOOZE_MS = 10 * 60_000L
    }
}
