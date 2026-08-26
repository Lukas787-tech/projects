package com.expensesplit.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.expensesplit.app.MainActivity
import com.expensesplit.app.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All user-facing notifications funnel through here so channels, permission checks and deep links
 * stay in one place.
 *
 * On Android 13+ POST_NOTIFICATIONS is a runtime permission; every post is guarded rather than
 * assumed, because a denied permission must degrade silently instead of throwing.
 */
@Singleton
class Notifier @Inject constructor(
    private val context: Context,
) {

    object Channels {
        const val BUDGET = "budget_alerts"
        const val BILLS = "bill_reminders"
        const val OFFERS = "price_offers"
        const val RECURRING = "recurring_expenses"
    }

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val channels = listOf(
            channel(
                Channels.BUDGET,
                R.string.channel_budget_name,
                R.string.channel_budget_description,
                NotificationManager.IMPORTANCE_HIGH,
            ),
            channel(
                Channels.BILLS,
                R.string.channel_bills_name,
                R.string.channel_bills_description,
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
            channel(
                Channels.OFFERS,
                R.string.channel_offers_name,
                R.string.channel_offers_description,
                NotificationManager.IMPORTANCE_LOW,
            ),
            channel(
                Channels.RECURRING,
                R.string.channel_recurring_name,
                R.string.channel_recurring_description,
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannels(channels)
    }

    fun notify(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        deepLink: String? = null,
        ongoing: Boolean = false,
    ) {
        if (!canPost()) return

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setContentIntent(pendingIntent(deepLink))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // A denied permission can still surface as a SecurityException on some OEM builds.
        runCatching { manager.notify(notificationId, notification) }
    }

    fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntent(deepLink: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            deepLink?.let { putExtra(MainActivity.EXTRA_DEEP_LINK, it) }
        }
        return PendingIntent.getActivity(
            context,
            deepLink?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun channel(id: String, nameRes: Int, descriptionRes: Int, importance: Int) =
        NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descriptionRes)
        }
}
