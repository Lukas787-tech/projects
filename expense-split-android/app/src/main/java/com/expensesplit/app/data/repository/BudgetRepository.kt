package com.expensesplit.app.data.repository

import com.expensesplit.app.core.DateRange
import com.expensesplit.app.data.local.dao.BudgetDao
import com.expensesplit.app.domain.analytics.BudgetEvaluator
import com.expensesplit.app.domain.model.Budget
import com.expensesplit.app.domain.model.BudgetProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val expenseRepository: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
) {

    val activeBudgets: Flow<List<Budget>> =
        budgetDao.observeActive().map { list -> list.map { it.toDomain() } }

    suspend fun getActive(): List<Budget> = budgetDao.getActive().map { it.toDomain() }

    suspend fun getById(id: Long): Budget? = budgetDao.getById(id)?.toDomain()

    suspend fun save(budget: Budget): Long = budgetDao.insert(budget.toEntity())

    suspend fun delete(id: Long) = budgetDao.deleteById(id)

    suspend fun forCategory(categoryId: Long): Budget? =
        budgetDao.getForCategory(categoryId)?.toDomain()

    /**
     * Evaluates every active budget against actual spend. Expenses are loaded once across the
     * widest period any budget covers, rather than per budget, to keep this cheap on the dashboard.
     */
    suspend fun evaluateAll(today: LocalDate = LocalDate.now()): List<BudgetProgress> {
        val budgets = getActive()
        if (budgets.isEmpty()) return emptyList()

        val earliest = budgets.minOf { BudgetEvaluator.currentPeriod(it, today).start }
        val latest = budgets.maxOf { BudgetEvaluator.currentPeriod(it, today).endInclusive }
        val expenses = expenseRepository.getExpensesIn(DateRange(earliest, latest))
        val categories = categoryRepository.getAllById()

        return BudgetEvaluator.evaluateAll(budgets, expenses, categories, today)
    }

    suspend fun alertsWorthSending(today: LocalDate = LocalDate.now()): List<BudgetProgress> =
        BudgetEvaluator.alertsWorthSending(evaluateAll(today))
}
