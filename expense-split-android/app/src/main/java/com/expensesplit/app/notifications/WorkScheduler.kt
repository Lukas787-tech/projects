package com.expensesplit.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers the app's periodic background work.
 *
 * Everything is enqueued with KEEP so re-running this on each launch is a no-op for already
 * scheduled work; only a genuinely new schedule replaces an old one. Initial delays line the jobs
 * up with times of day that make sense for what they report.
 */
@Singleton
class WorkScheduler @Inject constructor(
    private val context: Context,
) {

    fun scheduleAll() {
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniquePeriodicWork(
            RecurringExpenseWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecurringExpenseWorker>(Duration.ofHours(12))
                // Just after midnight, so a rule due today is posted early on the right date.
                .setInitialDelay(delayUntil(LocalTime.of(0, 30)))
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            BudgetAlertWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BudgetAlertWorker>(Duration.ofDays(1))
                .setInitialDelay(delayUntil(LocalTime.of(19, 0)))
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            BillReminderWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BillReminderWorker>(Duration.ofDays(3))
                .setInitialDelay(delayUntil(LocalTime.of(18, 0)))
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            SaleAlertWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SaleAlertWorker>(Duration.ofDays(2))
                // Price comparisons are only worth running when there is a network to check against.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(delayUntil(LocalTime.of(10, 0)))
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            MonthlyRecapWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonthlyRecapWorker>(Duration.ofDays(1))
                .setInitialDelay(delayUntil(LocalTime.of(9, 0)))
                .build(),
        )
    }

    fun cancelAll() {
        val workManager = WorkManager.getInstance(context)
        listOf(
            RecurringExpenseWorker.NAME,
            BudgetAlertWorker.NAME,
            BillReminderWorker.NAME,
            SaleAlertWorker.NAME,
            MonthlyRecapWorker.NAME,
        ).forEach(workManager::cancelUniqueWork)
    }

    /** Time from now until the next occurrence of [time], today or tomorrow. */
    private fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(time)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.ofMinutes(ChronoUnit.MINUTES.between(now, target))
    }
}
