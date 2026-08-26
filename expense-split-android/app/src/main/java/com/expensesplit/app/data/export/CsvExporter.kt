package com.expensesplit.app.data.export

import android.content.Context
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.SettlementSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CSV export for expenses and bill settlements.
 *
 * Fields are RFC-4180 quoted, and any value that could be read as a formula by a spreadsheet is
 * prefixed with an apostrophe — a merchant literally named `=cmd|...` should land in a cell as
 * text, not as something Excel tries to execute.
 */
@Singleton
class CsvExporter @Inject constructor(
    private val context: Context,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun exportExpenses(
        expenses: List<Expense>,
        baseCurrency: String,
        fileName: String = defaultFileName("expenses"),
    ): File = withContext(Dispatchers.IO) {
        val categories = categoryRepository.getAllById()
        val builder = StringBuilder()

        builder.appendLine(
            row(
                "Date", "Title", "Category", "Amount", "Currency",
                "Amount ($baseCurrency)", "FX rate", "Payment method", "Merchant", "Note",
                "Group", "Has receipt", "Settled",
            ),
        )

        expenses.sortedByDescending { it.date }.forEach { expense ->
            builder.appendLine(
                row(
                    expense.date.toString(),
                    expense.title,
                    categories[expense.categoryId]?.let { categoryRepository.displayName(it) }.orEmpty(),
                    Money.toEditableString(expense.amountMinor, expense.currency),
                    expense.currency,
                    Money.toEditableString(expense.baseAmountMinor, baseCurrency),
                    expense.fxRate.toString(),
                    expense.paymentMethod.name,
                    expense.merchant.orEmpty(),
                    expense.note.orEmpty(),
                    expense.groupId?.toString().orEmpty(),
                    if (expense.receiptId != null) "yes" else "no",
                    if (expense.isSettled) "yes" else "no",
                ),
            )
        }

        writeToExports(fileName, builder.toString())
    }

    suspend fun exportSettlements(
        groupName: String,
        currency: String,
        members: List<Member>,
        bills: List<Bill>,
        suggestions: List<SettlementSuggestion>,
        fileName: String = defaultFileName("settlement"),
    ): File = withContext(Dispatchers.IO) {
        val memberNames = members.associate { it.id to it.name }
        val builder = StringBuilder()

        builder.appendLine(row("Group", groupName))
        builder.appendLine(row("Currency", currency))
        builder.appendLine()

        builder.appendLine(row("Bills"))
        builder.appendLine(row("Date", "Title", "Total", "Paid by", "Split method", "Settled"))
        bills.sortedByDescending { it.date }.forEach { bill ->
            builder.appendLine(
                row(
                    bill.date.toString(),
                    bill.title,
                    Money.toEditableString(bill.totalMinor, bill.currency),
                    memberNames[bill.paidByMemberId].orEmpty(),
                    bill.splitMethod.name,
                    if (bill.settled) "yes" else "no",
                ),
            )
        }

        builder.appendLine()
        builder.appendLine(row("Who pays whom"))
        builder.appendLine(row("From", "To", "Amount", "Currency"))
        suggestions.forEach { suggestion ->
            builder.appendLine(
                row(
                    memberNames[suggestion.fromMemberId].orEmpty(),
                    memberNames[suggestion.toMemberId].orEmpty(),
                    Money.toEditableString(suggestion.amountMinor, suggestion.currency),
                    suggestion.currency,
                ),
            )
        }

        writeToExports(fileName, builder.toString())
    }

    private fun writeToExports(fileName: String, content: String): File {
        val directory = File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }
        return File(directory, fileName).apply { writeText(content, Charsets.UTF_8) }
    }

    private fun row(vararg values: String): String = values.joinToString(",") { escape(it) }

    private fun escape(value: String): String {
        val guarded = if (value.firstOrNull() in FORMULA_TRIGGERS) "'$value" else value
        val needsQuotes = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val quoted = guarded.replace("\"", "\"\"")
        return if (needsQuotes) "\"$quoted\"" else quoted
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

        fun defaultFileName(prefix: String): String {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            return "$prefix-$stamp.csv"
        }
    }
}
