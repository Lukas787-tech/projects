package com.lukas.jarvis.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lukas.jarvis.R
import com.lukas.jarvis.data.Task

/** Wraps AlarmManager so a task with a due date actually pings the phone. */
class Reminders(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Task and reminder alerts from Jarvis" }
        )
        notifications?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WAKE,
                context.getString(R.string.wake_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while Jarvis listens for the wake word" }
        )
    }

    fun schedule(task: Task) {
        val dueAt = task.dueAt ?: return
        if (!task.notify || task.done) return
        setAlarm(dueAt, pendingIntent(task))
    }

    /**
     * One extra ring for a task, without moving it: a snoozed repeating
     * reminder keeps its series and gets this beside it.
     */
    fun scheduleOnce(task: Task, at: Long) {
        setAlarm(at, pendingIntent(task, suffix = "/once"))
    }

    private fun setAlarm(dueAt: Long, pending: PendingIntent) {
        val manager = alarms ?: return

        // Exact alarms need an opt-in on newer Android. USE_EXACT_ALARM in the
        // manifest normally covers it, but a denied permission must not crash
        // the app, so fall back to an inexact alarm.
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        try {
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
        }
    }

    fun cancel(taskId: Long) {
        val manager = alarms ?: return
        // The task's own alarm, and the one-off ring a snooze set beside it.
        listOf("", "/once").forEach { suffix ->
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_FIRE
                data = android.net.Uri.parse("jarvis://task/$taskId$suffix")
            }
            val pending = PendingIntent.getBroadcast(
                context,
                taskId.toInt(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                manager.cancel(pending)
                pending.cancel()
            }
        }
    }

    fun rescheduleAll(tasks: List<Task>) {
        tasks.forEach { schedule(it) }
    }

    private fun pendingIntent(task: Task, suffix: String = ""): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            // A distinct data URI per task keeps PendingIntents from colliding.
            data = android.net.Uri.parse("jarvis://task/${task.id}$suffix")
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_NOTES, task.notes)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_REMINDERS = "jarvis_reminders"
        const val CHANNEL_WAKE = "jarvis_wake"
        const val ACTION_FIRE = "com.lukas.jarvis.REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NOTES = "notes"
    }
}
