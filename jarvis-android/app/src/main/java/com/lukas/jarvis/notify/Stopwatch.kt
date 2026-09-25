package com.lukas.jarvis.notify

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One stopwatch, kept across restarts: "start the stopwatch", "lap",
 * "how long has it been", "stop". While it runs or is paused it sits in the
 * notification shade with its own Pause, Resume and Reset buttons.
 */
class Stopwatch(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<StopwatchState> = _state.asStateFlow()

    init {
        runCatching {
            notifications?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Stopwatch", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "The running stopwatch" }
            )
        }
    }

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()): StopwatchState = change(_state.value.start(now))

    @Synchronized
    fun pause(now: Long = System.currentTimeMillis()): StopwatchState = change(_state.value.pause(now))

    @Synchronized
    fun lap(now: Long = System.currentTimeMillis()): StopwatchState = change(_state.value.lap(now))

    @Synchronized
    fun reset(): StopwatchState = change(_state.value.reset())

    private fun change(next: StopwatchState): StopwatchState {
        _state.value = next
        write(next)
        show(next)
        return next
    }

    /** Puts the notification back after a restart. */
    fun restore() = show(_state.value)

    private fun show(state: StopwatchState) {
        if (state.idle) {
            notifications?.cancel(NOTIFICATION)
            return
        }
        val now = System.currentTimeMillis()
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Stopwatch")
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentIntent(
                PendingIntent.getActivity(
                    app, NOTIFICATION,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        val lapLine = state.laps.takeIf { it.isNotEmpty() }?.let { "Lap ${it.size}: ${StopwatchState.clock(state.lapLengths().last())}" }
        if (state.running) {
            builder.setWhen(now - state.elapsed(now)).setShowWhen(true).setUsesChronometer(true)
                .setContentText(lapLine ?: "Running")
                .addAction(action("Pause", ACTION_PAUSE))
                .addAction(action("Lap", ACTION_LAP))
        } else {
            builder.setShowWhen(false)
                .setContentText("Paused at ${StopwatchState.clock(state.elapsed(now))}" + (lapLine?.let { " · $it" } ?: ""))
                .addAction(action("Resume", ACTION_RESUME))
        }
        builder.addAction(action("Reset", ACTION_RESET))
        runCatching { notifications?.notify(NOTIFICATION, builder.build()) }
    }

    private fun action(title: String, what: String): Notification.Action = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_notification),
        title,
        PendingIntent.getBroadcast(
            app, what.hashCode(),
            Intent(app, StopwatchReceiver::class.java).setAction(what),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    ).build()

    private fun read(): StopwatchState = runCatching {
        val o = JSONObject(prefs.getString(KEY, null) ?: return@runCatching StopwatchState())
        val laps = o.optJSONArray("laps") ?: JSONArray()
        StopwatchState(
            startedAt = if (o.has("started")) o.getLong("started") else null,
            bankedMs = o.optLong("banked"),
            laps = (0 until laps.length()).map { laps.getLong(it) }
        )
    }.getOrDefault(StopwatchState())

    private fun write(state: StopwatchState) {
        val o = JSONObject().put("banked", state.bankedMs).put("laps", JSONArray(state.laps))
        state.startedAt?.let { o.put("started", it) }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    companion object {
        const val STORE = "jarvis_stopwatch"
        private const val KEY = "state"
        private const val CHANNEL = "stopwatch"
        private const val NOTIFICATION = 83_500
        const val ACTION_PAUSE = "com.lukas.jarvis.stopwatch.PAUSE"
        const val ACTION_RESUME = "com.lukas.jarvis.stopwatch.RESUME"
        const val ACTION_LAP = "com.lukas.jarvis.stopwatch.LAP"
        const val ACTION_RESET = "com.lukas.jarvis.stopwatch.RESET"
    }
}

/** The buttons on the stopwatch's notification. */
class StopwatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stopwatch = (context.applicationContext as? JarvisApp)?.container?.stopwatch ?: return
        when (intent.action) {
            Stopwatch.ACTION_PAUSE -> stopwatch.pause()
            Stopwatch.ACTION_RESUME -> stopwatch.start()
            Stopwatch.ACTION_LAP -> stopwatch.lap()
            Stopwatch.ACTION_RESET -> stopwatch.reset()
        }
    }
}
