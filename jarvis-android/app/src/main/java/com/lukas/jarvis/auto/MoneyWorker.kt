package com.lukas.jarvis.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.R
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Entry
import com.lukas.jarvis.data.Money
import com.lukas.jarvis.data.Tracker
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Logs the rent, the subscriptions and the salary when they fall due, with
 * the app closed, and says so in one quiet notification, with a word about
 * any budget that this carried past four fifths or past all of itself.
 *
 * It only exists while something repeats: [MoneyWork.sync] keeps it while
 * there is a schedule and lets it go when the last one stops.
 */
class MoneyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? JarvisApp)?.container ?: return Result.failure()
        val brain = container.brain
        val now = System.currentTimeMillis()
        val logged = runCatching { brain.catchUpRecurring(now) }.getOrElse { return Result.retry() }
        if (logged.isNotEmpty()) {
            val trackers = brain.allTrackers(includeArchived = true).associateBy { it.id }
            val lines = logged.map { (rule, entry) ->
                val t = trackers[entry.trackerId]
                "${rule.note ?: t?.label ?: "Something"}: ${MoneyWork.format(entry.amount, t)}" +
                    if (entry.direction == Entry.DIR_IN) " in" else ""
            }
            val news = logged.map { it.second.trackerId }.distinct().mapNotNull { id ->
                trackers[id]?.let { MoneyWork.news(brain, it, logged.map { pair -> pair.second }, now) }
            }
            post(lines, news)
        }
        if (runCatching { brain.recurring() }.getOrDefault(emptyList()).isEmpty()) MoneyWork.stop(applicationContext)
        return Result.success()
    }

    private fun post(lines: List<String>, news: List<String>) {
        val context = applicationContext
        MoneyWork.ensureChannel(context)
        val intent = com.lukas.jarvis.surface.Entry.intent(context, com.lukas.jarvis.surface.Entry.MONEY)
        val open = PendingIntent.getActivity(
            context,
            "money-repeats".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (lines.size == 1) "Logged by itself" else "Logged by itself: ${lines.size} entries"
        val text = (lines + news).joinToString("\n")
        val notification = Notification.Builder(context, MoneyWork.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(MoneyWork.NOTIFICATION_ID, notification)
        }
    }
}

object MoneyWork {
    const val CHANNEL = "mochi_money"
    // Clear of the reminders' and the place alerts' ranges.
    const val NOTIFICATION_ID = 68_101
    private const val WORK = "money-repeats"

    /** Keeps the twice-daily check while anything repeats, and lets it go when nothing does. */
    fun sync(context: Context, brain: Brain) {
        val any = runCatching { brain.recurring().isNotEmpty() }.getOrDefault(false)
        if (!any) {
            stop(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<MoneyWorker>(12, TimeUnit.HOURS).build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Money that logs itself", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "When the rent, a subscription or a salary is logged by itself" }
        )
    }

    /** "Rent: 800.00 EUR". Counts drop a pointless ".00". */
    fun format(amount: Double, tracker: Tracker?): String {
        val number = if (tracker?.kind == Tracker.KIND_MONEY || tracker == null) {
            String.format(Locale.US, "%.2f", amount)
        } else if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
        return "$number ${tracker?.unit.orEmpty()}".trim()
    }

    /** What [logged] did to [tracker]'s budget, when it crossed four fifths or all of it. */
    fun news(brain: Brain, tracker: Tracker, logged: List<Entry>, now: Long): String? {
        val budget = tracker.budget ?: return null
        val window = Money.window(tracker.period, now) ?: return null
        val after = brain.trackerStatus(tracker).periodSpent
        val added = logged.filter { it.trackerId == tracker.id && it.direction == Entry.DIR_OUT && it.occurredAt >= window.start }
            .sumOf { it.amount }
        return Money.budgetNews(budget, after - added, after, tracker.period, window, now) { format(it, tracker) }
            ?.let { "${tracker.label}: $it" }
    }
}
