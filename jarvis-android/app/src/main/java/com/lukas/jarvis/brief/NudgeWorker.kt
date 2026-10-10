package com.lukas.jarvis.brief

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Streaks
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Looks, every couple of hours, at what the phone already knows (the
 * weather where it was last, the next appointments, the budgets, the habits,
 * what is due) and says at most one useful thing, by the rules in [Nudges].
 * It only exists while nudges are switched on.
 */
class NudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? JarvisApp)?.container ?: return Result.success()
        val settings = container.settings.current
        if (!settings.nudges) {
            NudgeWork.stop(applicationContext)
            return Result.success()
        }
        val kinds = Nudges.kinds(settings.nudgeKinds)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val brain = container.brain

        // The last place the phone was found, not a new fix: nothing here asks for location.
        val forecast = if (Nudges.RAIN in kinds && settings.weatherEnabled) {
            container.locator.remembered()?.let { point -> runCatching { container.weather.at(point, "Here", days = 1) }.getOrNull() }
        } else null
        val appointments = if (Nudges.EVENT in kinds && settings.calendarEnabled) {
            runCatching { container.agenda.between(now, now + 2 * 3_600_000L, 5) }.getOrDefault(emptyList())
        } else emptyList()
        val trackers = runCatching { brain.allTrackerStatus() }.getOrDefault(emptyList())
        val today = LocalDate.now(zone)
        val habits = if (Nudges.HABIT in kinds) {
            trackers.filter { Streaks.isHabit(it.tracker) }.mapNotNull { status ->
                runCatching {
                    val days = Streaks.days(brain.entriesBetween(now - 400L * 86_400_000L, now, status.tracker.id))
                    status.tracker to Streaks.of(days, today)
                }.getOrNull()
            }
        } else emptyList()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val due = runCatching { brain.tasks(includeDone = false, limit = 100) }.getOrDefault(emptyList())
            .filter { task -> task.dueAt?.let { it in start until end && it <= now } == true }

        val said = NudgeWork.said(applicationContext, now)
        val nudge = Nudges.pick(
            Nudges.Inputs(
                now = now,
                zone = zone,
                rainFrom = forecast?.rainFrom,
                rainingNow = (forecast?.now?.precipitationChance ?: 0) >= 60,
                appointments = appointments,
                trackers = trackers,
                habits = habits,
                dueToday = due,
                said = said
            ),
            kinds
        ) ?: return Result.success()
        NudgeWork.remember(applicationContext, said, nudge.key, now)
        post(nudge)
        return Result.success()
    }

    private fun post(nudge: Nudge) {
        val context = applicationContext
        NudgeWork.ensureChannel(context)
        // A tap asks what the nudge offered, as if it had been typed; or opens Today.
        val intent = nudge.ask?.let { ask ->
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, ask)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: com.lukas.jarvis.surface.Entry.intent(context, com.lukas.jarvis.surface.Entry.TODAY)
        val open = PendingIntent.getActivity(
            context,
            nudge.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, NudgeWork.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nudge.title)
            .setContentText(nudge.text)
            .setStyle(Notification.BigTextStyle().bigText(nudge.text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(NudgeWork.NOTIFICATION_ID, notification)
        }
    }
}

object NudgeWork {
    const val CHANNEL = "mochi_nudges"
    // One at a time: a newer nudge replaces an older one still showing.
    const val NOTIFICATION_ID = 68_201
    private const val WORK = "nudges"
    private const val STORE = "jarvis_nudges"
    private const val KEY = "said"

    /** Keeps the look every two hours while nudges are on, and lets it go when they are off. */
    fun sync(context: Context, settings: Settings) {
        if (!settings.nudges) {
            stop(context)
            return
        }
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NudgeWorker>(2, TimeUnit.HOURS).build()
            )
        }
    }

    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Nudges", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Now and then, one useful thing: rain on the way, an appointment to leave for" }
        )
    }

    /** What was said and when, kept to five weeks. */
    fun said(context: Context, now: Long): Map<String, Long> = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        Nudges.prune(json.keys().asSequence().associateWith { json.optLong(it) }, now)
    }.getOrDefault(emptyMap())

    fun remember(context: Context, said: Map<String, Long>, key: String, now: Long) {
        val next = JSONObject((said + (key to now)) as Map<*, *>)
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, next.toString()).apply()
    }
}
