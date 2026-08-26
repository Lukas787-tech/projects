package com.expensesplit.app.data.repository

import com.expensesplit.app.data.local.dao.ExpenseDao
import com.expensesplit.app.data.local.dao.ReceiptItemSearchRow
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.SearchFilters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val receiptRepository: ReceiptRepository,
) {

    fun search(filters: SearchFilters): Flow<List<Expense>> =
        expenseDao.searchRaw(SearchQueryBuilder.build(filters))
            .map { rows -> rows.map { it.toDomain() } }

    /** "When did I last buy coffee?" — searches line items across every scanned receipt. */
    suspend fun searchReceiptItems(query: String): List<ReceiptItemSearchRow> =
        if (query.isBlank()) emptyList() else receiptRepository.searchItems(query)
}
