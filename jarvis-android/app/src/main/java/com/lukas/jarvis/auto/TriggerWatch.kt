package com.lukas.jarvis.auto

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.control.Agenda
import java.util.concurrent.TimeUnit

/**
 * Watches for what starts a routine on its own, and only for what some
 * routine is waiting on: nothing runs in the background for a trigger
 * nobody set.
 *
 * - Bluetooth: the system's own connected and disconnected broadcasts, which
 *   reach the app with nothing of its own running.
 * - Charging: Android no longer tells a closed app about the plug, so work
 *   waits for "charging" and, once it has run, waits for the phone to be
 *   unplugged before it may run again. Plugging in is the moment, not being
 *   plugged in.
 * - A calendar event starting: an alarm at the next one's start, set again
 *   each time it goes off and whenever Mochi starts.
 */
object TriggerWatch {

    private const val CHARGE_WORK = "trigger-charging"
    const val ACTION_EVENT = "com.lukas.jarvis.TRIGGER_EVENT"

    /** A routine started by a trigger is not started again for this long: Bluetooth flickers. */
    private const val QUIET_FOR_MS = 10 * 60_000L

    fun sync(context: Context, routines: List<Routine>, agenda: Agenda) {
        val kinds = routines.mapNotNull { it.trigger?.kind }.toSet()
        if (Trigger.Kind.Charging in kinds) {
            // Already plugged in now is not "starting to charge": wait for the unplug first.
            ChargeWorker.enqueue(context, if (isCharging(context)) ChargeWorker.MODE_UNPLUG else ChargeWorker.MODE_PLUG, keep = true)
        } else {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(CHARGE_WORK) }
        }
        scheduleEvent(context, agenda, Trigger.Kind.EventStarts in kinds)
    }

    /** Something happened: start every routine waiting on it that has not just run. */
    fun fire(context: Context, happened: Trigger.Kind, device: String? = null) {
        val routines = (context.applicationContext as? JarvisApp)?.container?.routines ?: return
        val now = System.currentTimeMillis()
        routines.triggeredBy(happened, device)
            .filter { now - it.lastRunAt > QUIET_FOR_MS }
            .forEach { routine ->
                routines.markRun(routine.name)
                RoutineWorker.enqueue(context, routine.name)
            }
    }

    fun isCharging(context: Context): Boolean =
        runCatching { context.getSystemService(BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

    /** An alarm at the start of the next timed event in the coming week, or none. */
    fun scheduleEvent(context: Context, agenda: Agenda, wanted: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            ACTION_EVENT.hashCode(),
            Intent(context, RoutineReceiver::class.java).setAction(ACTION_EVENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { manager.cancel(pending) }
        if (!wanted || !agenda.hasPermission) return
        val now = System.currentTimeMillis()
        val next = agenda.between(now + 30_000L, now + 7 * 86_400_000L, 20).firstOrNull { !it.allDay } ?: return
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.startsAt, pending) }
    }

    /** Whether the phone will tell Mochi about Bluetooth devices: Android 12 asks for it. */
    fun canHearBluetooth(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

/** The car, the headphones: a Bluetooth device connecting or going. */
class BluetoothTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val happened = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Trigger.Kind.Connected
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Trigger.Kind.Disconnected
            else -> return
        }
        val name = runCatching {
            @Suppress("DEPRECATION")
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            device?.name
        }.getOrNull()
        TriggerWatch.fire(context, happened, name)
    }
}

/**
 * The plug, watched without anything of Mochi's running: one piece of work
 * that waits for charging and runs the routines, then one that looks every
 * quarter of an hour for the plug coming out, and starts the wait again.
 */
class ChargeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? JarvisApp)?.container ?: return Result.success()
        val wanted = container.routines.all.value.any { it.trigger?.kind == Trigger.Kind.Charging }
        if (!wanted) return Result.success()
        when (inputData.getString(KEY_MODE)) {
            MODE_PLUG -> {
                TriggerWatch.fire(applicationContext, Trigger.Kind.Charging)
                enqueue(applicationContext, MODE_UNPLUG, keep = false)
            }
            else -> enqueue(applicationContext, if (TriggerWatch.isCharging(applicationContext)) MODE_UNPLUG else MODE_PLUG, keep = false)
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "trigger-charging"
        private const val KEY_MODE = "mode"
        const val MODE_PLUG = "plug"
        const val MODE_UNPLUG = "unplug"

        fun enqueue(context: Context, mode: String, keep: Boolean) {
            val builder = OneTimeWorkRequestBuilder<ChargeWorker>().setInputData(workDataOf(KEY_MODE to mode))
            if (mode == MODE_PLUG) {
                builder.setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            } else {
                builder.setInitialDelay(15, TimeUnit.MINUTES)
            }
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK,
                    // From inside the running work, the next one waits for this one to finish.
                    if (keep) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE,
                    builder.build()
                )
            }
        }
    }
}
