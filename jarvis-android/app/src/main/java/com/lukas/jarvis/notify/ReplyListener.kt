package com.lukas.jarvis.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/** A message sitting in the shade that can be answered without opening its app. */
data class Answerable(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val from: String,
    val text: String,
    val postedAt: Long
)

/**
 * Replying to WhatsApp, Signal, Telegram and anything else, without touching them.
 *
 * Those apps have no interface a third app may call. What they do have is a
 * notification carrying a reply action, because that is how a smartwatch or a
 * car answers a message. Filling that action in and firing it is a supported
 * path rather than a trick, and the message goes out for real — there is no
 * screen to press send on.
 *
 * This deliberately keeps almost nothing. Notification access is the widest
 * permission in the app, so only notifications that actually carry a reply
 * action are retained, only the newest per conversation, only in memory, and
 * they are dropped the moment the notification is dismissed. Nothing is written
 * to disk and nothing is sent anywhere.
 */
class ReplyListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        // The shade already holds whatever arrived while this was not running.
        runCatching { activeNotifications }.getOrNull()?.forEach { remember(it) }
    }

    override fun onListenerDisconnected() {
        connected = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        remember(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        forget(sbn.key)
    }

    private fun forget(key: String) {
        synchronized(inbox) { inbox.remove(key) }
    }

    private fun remember(sbn: StatusBarNotification) {
        val action = replyAction(sbn.notification) ?: return
        if (action.remoteInputs.isNullOrEmpty()) return

        val extras = sbn.notification.extras
        val from = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        // A silent group summary carries the reply action but none of the
        // words, and answering it would answer nothing in particular.
        if (from.isBlank() && text.isBlank()) return

        val entry = Answerable(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labelOf(sbn.packageName),
            from = from,
            text = text,
            postedAt = sbn.postTime
        )
        synchronized(inbox) { inbox[sbn.key] = entry }
    }

    private fun labelOf(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    companion object {

        private const val MAX_KEPT = 40

        @Volatile
        private var connected: ReplyListener? = null

        /** Newest first. Held in memory only, and only while the shade holds them. */
        private val inbox = object : LinkedHashMap<String, Answerable>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answerable>) =
                size > MAX_KEPT
        }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val mine = ComponentName(context, ReplyListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(mine, ignoreCase = true) }
        }

        /** The system page where notification access is granted; there is no other. */
        fun permissionIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Posted on the main thread, read from whichever thread a tool runs
        // on, so every touch of the map is guarded.
        fun waiting(): List<Answerable> =
            synchronized(inbox) { inbox.values.toList() }.sortedByDescending { it.postedAt }

        /**
         * Finds the conversation the user means.
         *
         * With no name, the newest is the one meant — "reply, on my way" after a
         * message just arrived is the whole point. With a name, both the sender
         * and the app are searched, so "answer Anna" and "answer on WhatsApp"
         * both land.
         */
        fun match(who: String?): Answerable? {
            val all = waiting()
            val needle = who?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (needle.isBlank()) return all.firstOrNull()
            return all.firstOrNull { it.from.lowercase(Locale.ROOT).contains(needle) }
                ?: all.firstOrNull { it.appLabel.lowercase(Locale.ROOT).contains(needle) }
        }

        /** Fills in the notification's own reply box and fires it. */
        fun reply(target: Answerable, text: String): Boolean {
            val service = connected ?: return false
            val notification = runCatching {
                service.activeNotifications?.firstOrNull { it.key == target.key }?.notification
            }.getOrNull() ?: return false

            val action = replyAction(notification) ?: return false
            val inputs = action.remoteInputs ?: return false

            val carrier = Intent()
            val values = Bundle()
            inputs.forEach { input -> values.putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, carrier, values)
            // Marks it as typed rather than picked from a list, which is what
            // these actions expect and what stops some apps discarding the
            // reply. Added in Android 9; older phones simply do without.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(carrier, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }

            return runCatching {
                action.actionIntent.send(service, 0, carrier)
                true
            }.getOrDefault(false)
        }

        private fun replyAction(notification: Notification): Notification.Action? =
            notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }
}
