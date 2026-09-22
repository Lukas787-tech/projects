package com.lukas.jarvis.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import java.util.Locale

/**
 * Everything that is done by handing an intent to another app.
 *
 * This is the widest a sandboxed assistant can reach without a permission of its
 * own, and it reaches further than it first looks: alarms, timers, a dialled
 * number, a calendar entry and any installed app all go through the same door.
 * What none of them do is act *as* the user — a call is dialled but not placed —
 * so the last tap stays where it belongs. Saying that plainly in each reply is
 * why every method returns prose.
 *
 * Texts are the exception and no longer live here: with the SMS permission they
 * are sent outright by [com.lukas.jarvis.control.Messenger]. [composeSms] stays
 * as the fallback for when that permission has not been granted.
 */
class Launcher(context: Context) {

    private val app = context.applicationContext
    private val packages: PackageManager get() = app.packageManager

    // ------------------------------------------------------------ alarms

    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) return "That is not a time I can set."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            // Skipping the UI is what makes this feel like an assistant rather
            // than a shortcut into the clock app.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val time = String.format(Locale.US, "%02d:%02d", hour, minute)
        return if (start(intent)) {
            "Alarm set for $time" + (label?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + "."
        } else {
            "No clock app on this phone would take the alarm."
        }
    }

    fun setTimer(seconds: Int, label: String?): String {
        if (seconds <= 0) return "A timer needs a length."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) "Timer running for ${spoken(seconds)}." else
            "No clock app on this phone would take the timer."
    }

    fun showAlarms(): String {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Your alarms are on screen." else "No clock app answered."
    }

    private fun spoken(seconds: Int): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds % 3600 == 0 -> "${seconds / 3600} hour" + if (seconds > 3600) "s" else ""
        seconds < 3600 -> "${seconds / 60} minutes"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    // ----------------------------------------------------------------- apps

    /**
     * Opens an installed app by whatever the user called it.
     *
     * Names are matched loosely because speech gives "whats app" for WhatsApp
     * and "insta" for Instagram. An exact label wins, then a prefix, then a
     * contains — and when nothing matches, the closest few are named back so
     * the next attempt is a correction rather than another guess.
     */
    fun openApp(query: String): String {
        val wanted = query.trim().lowercase(Locale.ROOT)
        if (wanted.isBlank()) return "Which app?"
        val installed = launchable()
        if (installed.isEmpty()) {
            return "I cannot see the installed apps on this phone."
        }

        val match = installed.firstOrNull { it.first == wanted }
            ?: installed.firstOrNull { it.first.replace(" ", "") == wanted.replace(" ", "") }
            ?: installed.firstOrNull { it.first.startsWith(wanted) }
            ?: installed.firstOrNull { it.first.contains(wanted) }
            ?: installed.firstOrNull { wanted.contains(it.first) }

        if (match == null) {
            val near = installed.map { it.second }.take(6).joinToString(", ")
            return "I could not find an app called '$query'. Some of what is installed: $near."
        }

        val intent = packages.getLaunchIntentForPackage(match.third)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return "${match.second} is installed but has no screen to open."
        return if (start(intent)) "Opening ${match.second}." else "${match.second} would not open."
    }

    fun listApps(limit: Int = 30): String {
        val installed = launchable()
        if (installed.isEmpty()) return "I cannot see the installed apps on this phone."
        return "${installed.size} apps installed. A sample: " +
            installed.map { it.second }.shuffled().take(limit).sorted().joinToString(", ") + "."
    }

    /** (lowercase label, label, package) for everything with a launcher icon. */
    private fun launchable(): List<Triple<String, String, String>> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = packages.queryIntentActivities(intent, 0)
        resolved.mapNotNull { info ->
            val label = runCatching { info.loadLabel(packages).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            Triple(label.lowercase(Locale.ROOT), label, pkg)
        }.distinctBy { it.third }.sortedBy { it.first }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------- reaching people

    fun dial(number: String): String {
        val digits = number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        if (digits.isBlank()) return "That is not a number I can dial."
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // ACTION_DIAL, never ACTION_CALL: the call is teed up and the user
        // presses the green button. An app that places calls on its own is one
        // misheard word away from an expensive mistake.
        return if (start(intent)) {
            "$digits is in the dialler — press call when you are ready."
        } else {
            "This phone has no dialler I can reach."
        }
    }

    fun composeSms(number: String, body: String?): String {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return "I need a number to write to."
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).apply {
            if (!body.isNullOrBlank()) putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) {
            "Message to $digits is drafted and waiting for you to send it."
        } else {
            "No messaging app on this phone took the draft."
        }
    }

    fun composeEmail(to: String?, subject: String?, body: String?): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (!to.isNullOrBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            if (!subject.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (!body.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) {
            "Email drafted" + (to?.takeIf { it.isNotBlank() }?.let { " to $it" } ?: "") +
                ". Send it when you have read it over."
        } else {
            "No email app on this phone took the draft."
        }
    }

    fun share(text: String, title: String?): String {
        if (text.isBlank()) return "There was nothing to share."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, title ?: "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(chooser)) "Pick where it goes." else "Nothing on this phone can share text."
    }

    fun openUrl(url: String): String {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(intent)) "Opened $normalized in your browser." else
            "No browser on this phone would open it."
    }

    // --------------------------------------------------------------- calendar

    /**
     * A calendar entry, drafted in the user's own calendar app.
     *
     * Writing straight to the provider would need the write permission and would
     * put the event in whichever calendar this app guessed at. Handing over the
     * insert intent means the user's default calendar, their reminders and their
     * sharing settings all apply, at the cost of one confirmation tap.
     */
    fun createEvent(
        title: String,
        startMillis: Long,
        endMillis: Long?,
        location: String?,
        description: String?
    ): String {
        if (title.isBlank()) return "An event needs a title."
        val end = endMillis ?: (startMillis + 60 * 60 * 1000L)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            if (!location.isNullOrBlank()) {
                putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            }
            if (!description.isNullOrBlank()) {
                putExtra(CalendarContract.Events.DESCRIPTION, description)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (start(intent)) {
            "'$title' is filled in on your calendar — save it to keep it."
        } else {
            "No calendar app on this phone took the event."
        }
    }

    private fun start(intent: Intent): Boolean = runCatching {
        app.startActivity(intent)
        true
    }.getOrDefault(false)
}
