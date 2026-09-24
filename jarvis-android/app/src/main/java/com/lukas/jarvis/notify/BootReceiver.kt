package com.lukas.jarvis.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lukas.jarvis.data.Brain

/** Alarms do not survive a reboot or an app update, so rebuild them. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val brain = Brain(context)
                Reminders(context).rescheduleAll(brain.pendingReminders())
                com.lukas.jarvis.auto.Routines(context).rescheduleAll()
            } finally {
                pending.finish()
            }
        }.start()
    }
}
