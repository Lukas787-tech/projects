package com.lukas.jarvis.auto

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.core.Settings
import com.lukas.jarvis.data.Brain
import com.lukas.jarvis.data.Tidied
import com.lukas.jarvis.data.Upkeep
import java.util.concurrent.TimeUnit

/**
 * Memory upkeep, once a day while the phone charges: what [Upkeep] says only
 * gets in the way is put away (archived, never deleted), and listed for the
 * Library's "Tidied up" card, where each one can be brought back.
 */
class UpkeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? JarvisApp)?.container ?: return Result.success()
        if (!container.settings.current.tidyMemory) {
            UpkeepWork.stop(applicationContext)
            return Result.success()
        }
        UpkeepWork.tidy(applicationContext, container.brain)
        return Result.success()
    }
}

object UpkeepWork {
    private const val WORK = "memory-upkeep"
    private const val STORE = "jarvis_upkeep"
    private const val KEY = "tidied"

    fun sync(context: Context, settings: Settings) {
        if (!settings.tidyMemory) {
            stop(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpkeepWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) }
    }

    /** Puts away what the plan says and adds it to the list. Returns what it put away. */
    @Synchronized
    fun tidy(context: Context, brain: Brain, now: Long = System.currentTimeMillis()): List<Tidied> {
        val plan = Upkeep.plan(brain.recentMemories(2_000), now)
        val done = plan.filter { brain.setArchived(it.memoryId, true) }
        if (done.isNotEmpty()) write(context, (log(context) + done).takeLast(100))
        return done
    }

    /** What was put away and not yet looked at. */
    fun log(context: Context): List<Tidied> = Tidied.listFromJson(
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null)
    )

    /** Brings one back and takes it off the list. */
    fun bringBack(context: Context, brain: Brain, id: Long) {
        brain.setArchived(id, false)
        write(context, log(context).filterNot { it.memoryId == id })
    }

    /** "That's fine": the list is cleared, the memories stay put away. */
    fun clear(context: Context) = write(context, emptyList())

    private fun write(context: Context, list: List<Tidied>) {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, Tidied.listToJson(list)).apply()
    }
}
