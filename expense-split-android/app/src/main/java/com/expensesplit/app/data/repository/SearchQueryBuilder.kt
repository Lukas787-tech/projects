package com.expensesplit.app.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.expensesplit.app.domain.model.SearchFilters
import com.expensesplit.app.domain.model.SearchSort

/**
 * Assembles the WHERE clause for the advanced-search screen.
 *
 * Values are always passed as bound arguments — never interpolated — so a keyword containing a
 * quote is data, not SQL. Only the ORDER BY clause is chosen from a fixed set of literals.
 */
object SearchQueryBuilder {

    fun build(filters: SearchFilters): SupportSQLiteQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (filters.keyword.isNotBlank()) {
            // Escape LIKE wildcards so a literal % or _ in the keyword does not widen the match.
            val escaped = filters.keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            val pattern = "%$escaped%"
            clauses += """
                (title LIKE ? ESCAPE '\'
                 OR note LIKE ? ESCAPE '\'
                 OR merchant LIKE ? ESCAPE '\')
            """.trimIndent()
            repeat(3) { args += pattern }
        }

        filters.range?.let { range ->
            clauses += "date >= ? AND date <= ?"
            args += range.start.toEpochDay()
            args += range.endInclusive.toEpochDay()
        }

        if (filters.categoryIds.isNotEmpty()) {
            clauses += "categoryId IN (${placeholders(filters.categoryIds.size)})"
            args.addAll(filters.categoryIds)
        }

        if (filters.paymentMethods.isNotEmpty()) {
            clauses += "paymentMethod IN (${placeholders(filters.paymentMethods.size)})"
            args.addAll(filters.paymentMethods.map { it.name })
        }

        filters.minAmountMinor?.let {
            clauses += "baseAmountMinor >= ?"
            args += it
        }
        filters.maxAmountMinor?.let {
            clauses += "baseAmountMinor <= ?"
            args += it
        }

        if (filters.groupIds.isNotEmpty()) {
            clauses += "groupId IN (${placeholders(filters.groupIds.size)})"
            args.addAll(filters.groupIds)
        }

        filters.settledOnly?.let {
            clauses += "isSettled = ?"
            args += if (it) 1 else 0
        }

        if (filters.withReceiptOnly) {
            clauses += "receiptId IS NOT NULL"
        }

        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        val sql = "SELECT * FROM expenses $where ORDER BY ${orderBy(filters.sort)}"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private fun orderBy(sort: SearchSort): String = when (sort) {
        SearchSort.DATE_DESC -> "date DESC, createdAt DESC"
        SearchSort.DATE_ASC -> "date ASC, createdAt ASC"
        SearchSort.AMOUNT_DESC -> "baseAmountMinor DESC"
        SearchSort.AMOUNT_ASC -> "baseAmountMinor ASC"
        SearchSort.TITLE_ASC -> "title COLLATE NOCASE ASC"
    }
}
