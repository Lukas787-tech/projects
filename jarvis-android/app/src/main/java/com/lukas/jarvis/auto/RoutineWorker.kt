package com.lukas.jarvis.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lukas.jarvis.JarvisApp
import com.lukas.jarvis.MainActivity
import com.lukas.jarvis.R
import com.lukas.jarvis.data.ChatMessage
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * A quiet routine, run at its time with nobody watching.
 *
 * Every step goes through the assistant exactly as if it had been said, and
 * what comes back is sent as one notification — "no rain until the evening,
 * the 8:12 is on time" — and kept in the conversation, so the answer is there
 * when the app is next opened. When no model could be reached at all, it
 * waits for the network and tries again rather than sending an error.
 */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val container = (applicationContext as? JarvisApp)?.container ?: return Result.failure()
        val routine = container.routines.find(name) ?: return Result.success()
        val settings = container.settings.current

        var failed = 0
        val answer = runCatching {
            withTimeout(LIMIT_MS) {
                container.agent.runRoutine(routine, settings, onStepFailed = { failed++ })
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
                throw error
            }
            null
        }

        // Nothing got through: better a little late than a notification of errors.
        if (answer == null || failed == routine.steps.size) {
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            post(routine.name, "Couldn't run it this time — no model could be reached. Tap to try it yourself.", openRoutine = routine.name)
            return Result.success()
        }

        container.routines.markRun(routine.name)
        post(routine.name, answer, openRoutine = null)
        runCatching {
            container.brain.addMessage(
                ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "${title(routine.name)}: $answer")
            )
        }
        return Result.success()
    }

    /** Only asked for on Android 11 and older, where expedited work is a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureWorkingChannel(applicationContext)
        val notification = Notification.Builder(applicationContext, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Running a routine")
            .setOngoing(true)
            .build()
        return ForegroundInfo(WORKING_ID, notification)
    }

    private fun post(name: String, text: String, openRoutine: String?) {
        val context = applicationContext
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        openRoutine?.let { intent.putExtra(MainActivity.EXTRA_RUN_ROUTINE, it) }
        val open = PendingIntent.getActivity(
            context,
            ("quiet-$name").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, Routines.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(("routine-$name").hashCode(), notification)
        }
    }

    private fun title(name: String) = name.replaceFirstChar { it.uppercase() }

    companion object {
        private const val KEY_NAME = "routine"
        private const val LIMIT_MS = 4 * 60_000L
        private const val MAX_RETRIES = 3
        private const val CHANNEL_WORKING = "jarvis_working"
        private const val WORKING_ID = 71_001

        fun enqueue(context: Context, name: String) {
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(workDataOf(KEY_NAME to name))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork("routine-${name.lowercase()}", ExistingWorkPolicy.REPLACE, request)
            }
        }

        private fun ensureWorkingChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_WORKING, "Working in the background", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown briefly on older Android while a quiet routine runs" }
            )
        }
    }
}
