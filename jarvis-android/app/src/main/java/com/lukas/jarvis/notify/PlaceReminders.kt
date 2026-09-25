package com.lukas.jarvis.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.control.AskPermissionActivity
import com.lukas.jarvis.maps.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reminders that go off at a place: kept on the phone, watched by the system.
 *
 * The watching is the platform's proximity alert rather than a service of our
 * own polling the location, so nothing runs and no battery is spent between
 * crossings, and no Play Services are needed. Alerts do not survive a reboot,
 * so they are drawn again at start and at boot.
 */
class PlaceReminders(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jarvis_place_reminders", Context.MODE_PRIVATE)
    private val location = app.getSystemService(LocationManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    private val _all = MutableStateFlow(PlaceWatch.listFromJson(prefs.getString(KEY, null)))
    val all: StateFlow<List<PlaceWatch>> = _all.asStateFlow()

    val current: List<PlaceWatch> get() = _all.value

    /**
     * Precise location: Android refuses a proximity alert to an app that was
     * only allowed the approximate kind.
     */
    val canWatch: Boolean
        get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Location with the app closed. Without it Android hands the alerts only
     * to an app on screen, which is not when anyone arrives home.
     */
    val canWatchClosed: Boolean
        get() = canWatch && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )

    fun add(
        text: String,
        place: String,
        point: GeoPoint,
        leaving: Boolean,
        every: Boolean,
        here: GeoPoint?
    ): PlaceWatch {
        val id = (current.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(System.currentTimeMillis() / 1000) + 1
        val watch = PlaceWatch.create(id, text, place, point, leaving, every, here)
        write(current + watch)
        arm(watch)
        return watch
    }

    fun remove(id: Long): PlaceWatch? {
        val gone = current.firstOrNull { it.id == id } ?: return null
        disarm(gone)
        write(current - gone)
        return gone
    }

    fun find(wanted: String): PlaceWatch? = PlaceWatch.match(current, wanted)

    /**
     * Raises the location question: plain location first, and once that is
     * there, "all the time", which Android shows as its own settings page.
     */
    fun askPermission() {
        if (!canWatch) {
            AskPermissionActivity.ask(
                app,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !canWatchClosed) {
            AskPermissionActivity.ask(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    /** Draws every circle again: after a reboot, an update or a restore. */
    fun armAll() {
        current.forEach(::arm)
    }

    /** Reads the store again, after a restore wrote it behind this class's back. */
    fun reload() {
        current.forEach(::disarm)
        _all.value = PlaceWatch.listFromJson(prefs.getString(KEY, null))
        armAll()
    }

    /** The phone crossed a circle. Returns the reminder when it went off. */
    internal fun crossed(id: Long, entering: Boolean): PlaceWatch? {
        val watch = current.firstOrNull { it.id == id } ?: return null
        return when (watch.onCrossing(entering)) {
            PlaceWatch.Crossing.Ignore -> null
            PlaceWatch.Crossing.Arm -> {
                write(current.map { if (it.id == id) it.copy(armed = true) else it })
                null
            }
            PlaceWatch.Crossing.Fire -> {
                post(watch)
                val next = watch.afterFiring()
                if (next == null) {
                    disarm(watch)
                    write(current.filterNot { it.id == id })
                } else {
                    write(current.map { if (it.id == id) next else it })
                }
                watch
            }
        }
    }

    fun describe(): String {
        if (current.isEmpty()) return "No place reminders."
        return current.joinToString("\n") { "• [${it.id}] ${it.describe()}" }
    }

    private fun arm(watch: PlaceWatch) {
        val manager = location ?: return
        if (!canWatch) return
        // A refusal here (permission withdrawn, no location provider at all)
        // leaves the reminder stored and listed, to be armed at the next start.
        runCatching {
            manager.addProximityAlert(
                watch.point.lat,
                watch.point.lon,
                watch.radius.toFloat(),
                -1L,
                pending(watch.id)
            )
        }
    }

    private fun disarm(watch: PlaceWatch) {
        runCatching { location?.removeProximityAlert(pending(watch.id)) }
    }

    /**
     * Mutable on purpose: the location service writes whether this was an
     * entry or an exit into the intent, and an immutable one arrives without it.
     */
    private fun pending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        app,
        id.toInt(),
        Intent(app, PlaceReceiver::class.java)
            .setAction(ACTION_CROSSED)
            .setData(Uri.parse("jarvis://place/$id"))
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    )

    private fun post(watch: PlaceWatch) {
        Reminders(app) // makes sure the reminders channel exists
        val open = PendingIntent.getActivity(
            app,
            watch.id.toInt(),
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val heading = "Set for ${watch.trigger}"
        val notification = Notification.Builder(app, Reminders.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(watch.text.replaceFirstChar { it.uppercase() })
            .setContentText(heading)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { notifications?.notify(NOTIFICATION_BASE + (watch.id % 10_000).toInt(), notification) }
    }

    private fun write(all: List<PlaceWatch>) {
        prefs.edit().putString(KEY, PlaceWatch.listToJson(all)).apply()
        _all.value = all
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val STORE = "jarvis_place_reminders"
        private const val KEY = "reminders"
        const val ACTION_CROSSED = "com.lukas.jarvis.PLACE_CROSSED"
        const val EXTRA_ID = "place_reminder_id"
        private const val NOTIFICATION_BASE = 70_000
    }
}

/** The location service saying the phone crossed one of the circles. */
class PlaceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlaceReminders.ACTION_CROSSED) return
        val app = context.applicationContext as? JarvisApp ?: return
        val container = app.container
        val id = intent.getLongExtra(PlaceReminders.EXTRA_ID, -1L)
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val fired = container.placeReminders.crossed(id, entering) ?: return
        if (app.inForeground && container.settings.current.speakReplies) {
            runCatching { container.speaker.speak("Reminder: ${fired.text}.") { } }
        }
    }
}
