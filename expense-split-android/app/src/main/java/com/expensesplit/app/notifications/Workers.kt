package com.expensesplit.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.expensesplit.app.R
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.AnalyticsRepository
import com.expensesplit.app.data.repository.BudgetRepository
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.data.repository.PriceRepository
import com.expensesplit.app.data.repository.RecurringRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

/**
 * Budget warnings. Runs daily; only categories that are actually near or over their limit produce
 * a notification, so a user comfortably within budget never hears from this worker.
 */
@HiltWorker
class BudgetAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.notificationsEnabled || !preferences.budgetAlertsEnabled) {
            return Result.success()
        }

        budgetRepository.alertsWorthSending().forEach { progress ->
            val name = progress.category?.let { categoryRepository.displayName(it) }
                ?: applicationContext.getString(R.string.budget_overall)

            val (title, body) = if (progress.isOverBudget) {
                applicationContext.getString(R.string.notification_budget_over_title, name) to
                    applicationContext.getString(
                        R.string.notification_budget_over_body,
                        Money.format(progress.spentMinor - progress.limitMinor, progress.currency),
                        Money.format(progress.limitMinor, progress.currency),
                    )
            } else {
                applicationContext.getString(R.string.notification_budget_near_title, name) to
                    applicationContext.getString(
                        R.string.notification_budget_near_body,
                        (progress.usedFraction * 100).toInt(),
                        Money.format(progress.remainingMinor, progress.currency),
                    )
            }

            notifier.notify(
                channelId = Notifier.Channels.BUDGET,
                // Stable per budget, so a re-run updates the existing notification rather than stacking.
                notificationId = NOTIFICATION_BASE + progress.budget.id.toInt(),
                title = title,
                body = body,
                deepLink = DeepLinks.ANALYTICS,
            )
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val NAME = "budget_alerts"
        private const val NOTIFICATION_BASE = 1000
    }
}

/** Posts expenses for any recurring rule that has come due, then reports what it created. */
@HiltWorker
class RecurringExpenseWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.recurringAutoPost) return Result.success()

        val created = recurringRepository.materializeDue(preferences.baseCurrency)
        if (created.isNotEmpty() && preferences.notificationsEnabled) {
            notifier.notify(
                channelId = Notifier.Channels.RECURRING,
                notificationId = NOTIFICATION_ID,
                title = applicationContext.getString(R.string.notification_recurring_title),
                body = applicationContext.resources.getQuantityString(
                    R.plurals.notification_recurring_body,
                    created.size,
                    created.size,
                ),
                deepLink = DeepLinks.EXPENSES,
            )
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val NAME = "recurring_expenses"
        private const val NOTIFICATION_ID = 2000
    }
}

/** Reminds about groups with unsettled balances. */
@HiltWorker
class BillReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val analyticsRepository: AnalyticsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.notificationsEnabled || !preferences.billRemindersEnabled) {
            return Result.success()
        }

        val outstanding = analyticsRepository.settlementSummaries()
            .filter { it.youAreOwedMinor > 0 || it.youOweMinor > 0 }
        if (outstanding.isEmpty()) return Result.success()

        val owed = outstanding.sumOf { it.youAreOwedMinor }
        val owing = outstanding.sumOf { it.youOweMinor }
        val currency = outstanding.first().currency

        notifier.notify(
            channelId = Notifier.Channels.BILLS,
            notificationId = NOTIFICATION_ID,
            title = applicationContext.getString(R.string.notification_bills_title),
            body = applicationContext.getString(
                R.string.notification_bills_body,
                Money.format(owed, currency),
                Money.format(owing, currency),
            ),
            deepLink = DeepLinks.BILLS,
        )
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val NAME = "bill_reminders"
        private const val NOTIFICATION_ID = 3000
    }
}

/** Tells the user when something they buy regularly is cheaper somewhere they have shopped. */
@HiltWorker
class SaleAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val priceRepository: PriceRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.notificationsEnabled || !preferences.saleAlertsEnabled) {
            return Result.success()
        }

        val best = priceRepository.saleAlerts().maxByOrNull { it.savingMinor } ?: return Result.success()

        notifier.notify(
            channelId = Notifier.Channels.OFFERS,
            notificationId = NOTIFICATION_ID,
            title = applicationContext.getString(R.string.notification_sale_title, best.displayName),
            body = applicationContext.getString(
                R.string.notification_sale_body,
                best.bestStore,
                Money.format(best.savingMinor, best.currency),
                best.savingPercent.toInt(),
            ),
            deepLink = DeepLinks.RECEIPTS,
        )
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val NAME = "sale_alerts"
        private const val NOTIFICATION_ID = 4000
    }
}

/** Nudges the user to open the finished recap once a month has closed. */
@HiltWorker
class MonthlyRecapWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val analyticsRepository: AnalyticsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val preferences = preferencesRepository.preferences.first()
        if (!preferences.notificationsEnabled) return Result.success()

        val lastMonth = YearMonth.from(LocalDate.now()).minusMonths(1)
        val recap = analyticsRepository.monthlyRecap(lastMonth, preferences.baseCurrency)
        if (recap.report.transactionCount == 0) return Result.success()

        notifier.notify(
            channelId = Notifier.Channels.BILLS,
            notificationId = NOTIFICATION_ID,
            title = applicationContext.getString(R.string.notification_recap_title),
            body = applicationContext.getString(
                R.string.notification_recap_body,
                Money.format(recap.report.totalMinor, recap.currency),
                recap.report.transactionCount,
            ),
            deepLink = DeepLinks.RECAP,
        )
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val NAME = "monthly_recap"
        private const val NOTIFICATION_ID = 5000
    }
}

object DeepLinks {
    const val DASHBOARD = "expensesplit://dashboard"
    const val EXPENSES = "expensesplit://expenses"
    const val BILLS = "expensesplit://bills"
    const val ANALYTICS = "expensesplit://analytics"
    const val RECAP = "expensesplit://recap"
    const val RECEIPTS = "expensesplit://receipts"
}
