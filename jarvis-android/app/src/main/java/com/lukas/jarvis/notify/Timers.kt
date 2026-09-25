package com.lukas.jarvis.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A countdown Jarvis is running. */
data class RunningTimer(
    val id: Int,
    val label: String,
    val endsAt: Long,
    val lengthMs: Long,
    /** A sleep timer: at the end the music stops, and nothing rings. */
    val sleep: Boolean = false
) {
    fun leftMs(now: Long = System.currentTimeMillis()): Long = (endsAt - now).coerceAtLeast(0)
}

/**
 * Jarvis's own timers.
 *
 * The clock app's timers are a black box to every other app: nothing can ask
 * how long is left, name one, or stop the pasta but not the laundry. These
 * can. Each is an exact alarm, a countdown in the notification shade, a
 * sound that keeps going until it is stopped, and a line on the assistant's
 * screen — and "how long is left on the pasta" has an answer.
 */
class Timers(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<RunningTimer>> = _all.asStateFlow()

    private val _ringing = MutableStateFlow<List<RunningTimer>>(emptyList())

    /** Timers that have run out and are still sounding, until stopped. */
    val ringing: StateFlow<List<RunningTimer>> = _ringing.asStateFlow()

    init {
        ensureChannels()
    }

    @Synchronized
    fun start(seconds: Int, label: String?, sleep: Boolean = false): RunningTimer {
        val now = System.currentTimeMillis()
        val id = ((prefs.getInt(KEY_NEXT, 1)).also { prefs.edit().putInt(KEY_NEXT, if (it >= 9_000) 1 else it + 1).apply() })
        val name = label?.trim()?.takeIf { it.isNotBlank() }
            ?: if (sleep) "Music off" else defaultName(seconds)
        val timer = RunningTimer(id, name, now + seconds * 1000L, seconds * 1000L, sleep)
        write(_all.value + timer)
        arm(timer)
        showRunning(timer)
        return timer
    }

    /** Stops the timers [which] names — a label, "all", or blank for the newest. */
    @Synchronized
    fun cancel(which: String?): List<RunningTimer> {
        val gone = pick(which)
        gone.forEach { disarm(it) }
        write(_all.value - gone.toSet())
        return gone
    }

    @Synchronized
    fun cancelId(id: Int) {
        _all.value.firstOrNull { it.id == id }?.let { timer ->
            disarm(timer)
            write(_all.value - timer)
        }
    }

    /** Adds (or with a negative number takes away) time from the named timer. */
    @Synchronized
    fun extend(which: String?, seconds: Int): RunningTimer? {
        val timer = pick(which).firstOrNull() ?: return null
        val moved = timer.copy(
            endsAt = (timer.endsAt + seconds * 1000L).coerceAtLeast(System.currentTimeMillis() + 1000),
            lengthMs = (timer.lengthMs + seconds * 1000L).coerceAtLeast(1000)
        )
        write(_all.value.map { if (it.id == timer.id) moved else it })
        arm(moved)
        showRunning(moved)
        return moved
    }

    /** "Pasta: 4 minutes 12 seconds left", one line per timer. */
    fun describe(now: Long = System.currentTimeMillis()): String {
        val running = _all.value.filter { it.endsAt > now }.sortedBy { it.endsAt }
        if (running.isEmpty()) return "No timers are running."
        return running.joinToString("\n") { "${it.label}: ${spoken(it.leftMs(now))} left" }
    }

    /** Called by the alarm: the timer is over, whatever else happens. */
    @Synchronized
    internal fun finished(id: Int): RunningTimer? {
        val timer = _all.value.firstOrNull { it.id == id } ?: return null
        write(_all.value - timer)
        notifications?.cancel(runningId(id))
        return timer
    }

    /**
     * Rings any timer whose end has passed without its alarm arriving — one
     * Android held back, or lost to a force-stop — so nothing sits at 0:00.
     */
    @Synchronized
    fun catchUp(now: Long = System.currentTimeMillis(), grace: Long = 3_000L): List<RunningTimer> {
        val late = _all.value.filter { it.endsAt + grace < now }
        late.forEach { timer -> finished(timer.id)?.let { end(it) } }
        return late
    }

    /** Alarms do not survive a reboot; a timer that ended meanwhile rings at once. */
    fun rescheduleAll() {
        _all.value.forEach { arm(it); showRunning(it) }
    }

    fun reload() {
        _all.value = read()
    }

    // ---------------------------------------------------------------- details

    private fun pick(which: String?): List<RunningTimer> {
        val running = _all.value.sortedBy { it.endsAt }
        val wanted = which?.trim()?.lowercase(Locale.ROOT).orEmpty()
            .removePrefix("the ").removeSuffix(" timer").trim()
        return when {
            running.isEmpty() -> emptyList()
            wanted == "all" || wanted == "every" || wanted == "alle" -> running
            wanted.isBlank() || wanted == "timer" -> listOf(running.maxBy { it.id })
            else -> running.filter { it.label.lowercase(Locale.ROOT).contains(wanted) }
                .ifEmpty { running.filter { wanted.contains(it.label.lowercase(Locale.ROOT)) } }
        }
    }

    private fun arm(timer: RunningTimer) {
        val manager = alarms ?: return
        val pending = firePending(timer.id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            // A timer is the one thing that must go off on the second: an
            // alarm clock entry is exact even in deep doze.
            if (exact) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(timer.endsAt, openApp()), pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endsAt, pending)
        }
    }

    private fun disarm(timer: RunningTimer) {
        alarms?.cancel(firePending(timer.id))
        notifications?.cancel(runningId(timer.id))
        notifications?.cancel(doneId(timer.id))
    }

    private fun showRunning(timer: RunningTimer) {
        val cancel = PendingIntent.getBroadcast(
            app,
            runningId(timer.id),
            Intent(app, TimerReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, timer.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(app, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(timer.label)
            .setContentText("Timer")
            .setWhen(timer.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(icon(), "Cancel", cancel).build())
            .build()
        runCatching { notifications?.notify(runningId(timer.id), notification) }
    }

    /**
     * A timer's end: a sleep timer pauses whatever is playing and says so
     * quietly; any other rings.
     */
    internal fun end(timer: RunningTimer) {
        if (!timer.sleep) {
            ring(timer)
            return
        }
        runCatching {
            val audio = app.getSystemService(android.media.AudioManager::class.java)
            listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach { action ->
                audio?.dispatchMediaKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            }
        }
        val notification = Notification.Builder(app, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Music paused")
            .setContentText("Your sleep timer ran out. Good night.")
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .build()
        runCatching { notifications?.notify(doneId(timer.id), notification) }
    }

    internal fun ring(timer: RunningTimer) {
        val stop = PendingIntent.getBroadcast(
            app,
            doneId(timer.id),
            Intent(app, TimerReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, timer.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(app, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${timer.label} — time's up")
            .setContentText("Tap Stop to silence it")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(icon(), "Stop", stop).build())
            .build()
        // Keeps sounding until it is stopped, like a kitchen timer.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        runCatching { notifications?.notify(doneId(timer.id), notification) }
        _ringing.value = _ringing.value.filterNot { it.id == timer.id } + timer
    }

    fun silence(id: Int) {
        notifications?.cancel(doneId(id))
        _ringing.value = _ringing.value.filterNot { it.id == id }
    }

    private fun icon() = android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_notification)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        app,
        REQUEST_OPEN,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun firePending(id: Int): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_FIRE + id,
        Intent(app, TimerReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannels() {
        val manager = notifications ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Timers running", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "The countdown of each running timer" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Timer finished", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sounds when a timer runs out"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    private fun read(): List<RunningTimer> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            RunningTimer(o.optInt("id"), o.optString("label"), o.optLong("ends"), o.optLong("length"), o.optBoolean("sleep"))
        }
    }.getOrDefault(emptyList())

    private fun write(timers: List<RunningTimer>) {
        val array = JSONArray()
        timers.forEach {
            array.put(JSONObject().put("id", it.id).put("label", it.label).put("ends", it.endsAt).put("length", it.lengthMs).put("sleep", it.sleep))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _all.value = timers
    }

    companion object {
        const val STORE = "jarvis_timers"
        private const val KEY = "timers"
        private const val KEY_NEXT = "next_id"
        const val CHANNEL_RUNNING = "timers_running"
        const val CHANNEL_DONE = "timers_done"
        const val ACTION_FIRE = "com.lukas.jarvis.TIMER_FIRE"
        const val ACTION_CANCEL = "com.lukas.jarvis.TIMER_CANCEL"
        const val ACTION_STOP = "com.lukas.jarvis.TIMER_STOP"
        const val EXTRA_ID = "timer_id"
        private const val REQUEST_OPEN = 8100
        private const val REQUEST_FIRE = 81_000

        private fun runningId(id: Int) = 82_000 + id
        private fun doneId(id: Int) = 92_000 + id

        fun defaultName(seconds: Int): String = "${spoken(seconds * 1000L)} timer"

        /** "4 minutes 12 seconds", "1 hour 5 minutes", "30 seconds". */
        fun spoken(ms: Long): String {
            val total = ((ms + 999) / 1000).toInt()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            fun unit(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { unit(it, "hour") },
                m.takeIf { it > 0 }?.let { unit(it, "minute") },
                s.takeIf { it > 0 && h == 0 }?.let { unit(it, "second") }
            ).joinToString(" ").ifBlank { "0 seconds" }
        }
    }
}

/** A timer's end, and the Cancel and Stop buttons on its notifications. */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? JarvisApp)?.container ?: return
        val timers = container.timers
        val id = intent.getIntExtra(Timers.EXTRA_ID, -1)
        when (intent.action) {
            Timers.ACTION_FIRE -> {
                val timer = timers.finished(id) ?: return
                timers.end(timer)
                // With the app in front of them, the assistant says it too.
                val app = context.applicationContext as JarvisApp
                if (!timer.sleep && app.inForeground && container.settings.current.speakReplies) {
                    val address = com.lukas.jarvis.llm.Personas.address(container.settings.current)
                    val lead = if (address.isBlank()) "Time's up" else "Time's up, $address"
                    runCatching { container.speaker.announce("$lead: ${timer.label}.") }
                }
            }
            Timers.ACTION_CANCEL -> timers.cancelId(id)
            Timers.ACTION_STOP -> timers.silence(id)
        }
    }
}
