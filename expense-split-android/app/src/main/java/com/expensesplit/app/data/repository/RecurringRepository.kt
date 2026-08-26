package com.expensesplit.app.data.repository

import com.expensesplit.app.data.local.dao.RecurringDao
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.RecurringRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecurringRepository @Inject constructor(
    private val recurringDao: RecurringDao,
    private val expenseRepository: ExpenseRepository,
) {

    val rules: Flow<List<RecurringRule>> =
        recurringDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<RecurringRule> = recurringDao.getAll().map { it.toDomain() }

    suspend fun getById(id: Long): RecurringRule? = recurringDao.getById(id)?.toDomain()

    suspend fun save(rule: RecurringRule): Long = recurringDao.insert(rule.toEntity())

    suspend fun delete(id: Long) = recurringDao.deleteById(id)

    suspend fun setActive(id: Long, active: Boolean) {
        val rule = recurringDao.getById(id) ?: return
        recurringDao.update(rule.copy(active = active))
    }

    suspend fun dueRules(today: LocalDate = LocalDate.now()): List<RecurringRule> =
        recurringDao.getDue(today).map { it.toDomain() }

    /**
     * Posts every occurrence a rule has missed and advances it past today.
     *
     * The loop catches up on rules that were due while the app was closed — reopening after a month
     * away creates each missed instance on its real date, not one lump entry today. The iteration
     * cap is a guard against a corrupted rule with a zero interval spinning forever.
     */
    suspend fun materializeDue(baseCurrency: String, today: LocalDate = LocalDate.now()): List<Long> {
        val created = mutableListOf<Long>()

        for (rule in dueRules(today)) {
            var cursor = rule.nextRunDate
            var lastPosted: LocalDate? = rule.lastRunDate
            var iterations = 0

            while (!cursor.isAfter(today) && iterations < MAX_CATCH_UP) {
                if (rule.hasEnded(cursor)) break

                created += expenseRepository.save(
                    Expense(
                        title = rule.title,
                        categoryId = rule.categoryId,
                        amountMinor = rule.amountMinor,
                        currency = rule.currency,
                        baseAmountMinor = rule.amountMinor,
                        date = cursor,
                        paymentMethod = rule.paymentMethod,
                        merchant = rule.merchant,
                        recurringRuleId = rule.id,
                    ),
                    baseCurrency,
                )
                lastPosted = cursor
                cursor = rule.advanceFrom(cursor)
                iterations++
            }

            val stillActive = rule.endDate == null || !cursor.isAfter(rule.endDate)
            recurringDao.update(
                rule.copy(
                    nextRunDate = cursor,
                    lastRunDate = lastPosted,
                    active = rule.active && stillActive,
                ).toEntity(),
            )
        }
        return created
    }

    private companion object {
        /** A daily rule dormant for ~3 years is the realistic worst case. */
        const val MAX_CATCH_UP = 1200
    }
}
