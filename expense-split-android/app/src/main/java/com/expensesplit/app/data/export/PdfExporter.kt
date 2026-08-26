package com.expensesplit.app.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.expensesplit.app.R
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.repository.CategoryRepository
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.MonthlyRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the monthly recap as a PDF using the platform's own [PdfDocument].
 *
 * Drawing by hand rather than pulling in a PDF library keeps the APK small and the output fully
 * offline. Layout is a simple top-down cursor with explicit page breaks: every section asks for the
 * height it needs before drawing, and starts a new page when the cursor would overflow the margin.
 */
@Singleton
class PdfExporter @Inject constructor(
    private val context: Context,
    private val categoryRepository: CategoryRepository,
) {

    private companion object {
        // A4 at 72dpi, the unit PdfDocument works in.
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 42f
        const val LINE = 16f

        const val COLOR_TEXT = 0xFF1B1B1F.toInt()
        const val COLOR_MUTED = 0xFF6F6F78.toInt()
        const val COLOR_ACCENT = 0xFF3949AB.toInt()
        const val COLOR_RULE = 0xFFDDDDE3.toInt()
        const val COLOR_OVER = 0xFFE53935.toInt()
        const val COLOR_OK = 0xFF43A047.toInt()
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 10.5f
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_MUTED
        textSize = 9.5f
    }
    private val rulePaint = Paint().apply {
        color = COLOR_RULE
        strokeWidth = 0.8f
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Rendering state for one document: the current page, canvas and vertical cursor. */
    private class Layout(val document: PdfDocument) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y: Float = MARGIN
        var pageNumber: Int = 0
    }

    suspend fun exportRecap(
        recap: MonthlyRecap,
        expenses: List<Expense>,
        locale: Locale = Locale.getDefault(),
        fileName: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        val layout = Layout(document)
        val currency = recap.currency

        startPage(layout)
        drawHeader(layout, recap, locale)
        drawSummary(layout, recap, currency, locale)
        drawCategoryBreakdown(layout, recap, currency, locale)
        drawBudgets(layout, recap, currency, locale)
        drawInsights(layout, recap)
        drawMerchants(layout, recap, currency, locale)
        drawSettlements(layout, recap, locale)
        drawExpenseTable(layout, expenses, currency, locale)
        finishPage(layout)

        val monthLabel = String.format(
            Locale.ROOT,
            "%04d-%02d",
            recap.month.year,
            recap.month.monthValue,
        )
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val target = File(directory, fileName ?: "recap-$monthLabel.pdf")
        target.outputStream().use { document.writeTo(it) }
        document.close()
        target
    }

    private fun startPage(layout: Layout) {
        layout.pageNumber += 1
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, layout.pageNumber).create()
        val page = layout.document.startPage(info)
        layout.page = page
        layout.canvas = page.canvas
        layout.y = MARGIN
    }

    private fun finishPage(layout: Layout) {
        val page = layout.page ?: return
        // Footer sits at a fixed offset from the bottom, independent of the content cursor.
        page.canvas.drawText(
            context.getString(R.string.pdf_footer, layout.pageNumber),
            MARGIN,
            PAGE_HEIGHT - MARGIN / 2,
            mutedPaint,
        )
        layout.document.finishPage(page)
        layout.page = null
        layout.canvas = null
    }

    /** Guarantees [needed] points of vertical room, breaking to a new page when short. */
    private fun ensureSpace(layout: Layout, needed: Float) {
        if (layout.y + needed <= PAGE_HEIGHT - MARGIN * 1.5f) return
        finishPage(layout)
        startPage(layout)
    }

    private fun drawHeader(layout: Layout, recap: MonthlyRecap, locale: Locale) {
        val canvas = layout.canvas ?: return
        val monthName = recap.month.month.getDisplayName(TextStyle.FULL, locale)
            .replaceFirstChar { it.titlecase(locale) }

        canvas.drawText(context.getString(R.string.recap_title), MARGIN, layout.y + 18f, titlePaint)
        layout.y += 26f
        canvas.drawText("$monthName ${recap.month.year}", MARGIN, layout.y + 12f, bodyPaint)
        layout.y += 22f
        canvas.drawLine(MARGIN, layout.y, PAGE_WIDTH - MARGIN, layout.y, rulePaint)
        layout.y += 18f
    }

    private fun drawSummary(layout: Layout, recap: MonthlyRecap, currency: String, locale: Locale) {
        ensureSpace(layout, 110f)
        val canvas = layout.canvas ?: return
        val report = recap.report

        section(layout, context.getString(R.string.recap_section_summary))

        val rows = listOf(
            context.getString(R.string.recap_total_spent) to
                Money.format(report.totalMinor, currency, locale),
            context.getString(R.string.recap_transactions) to report.transactionCount.toString(),
            context.getString(R.string.recap_daily_average) to
                Money.format(report.averagePerDayMinor, currency, locale),
            context.getString(R.string.recap_average_transaction) to
                Money.format(report.averagePerTransactionMinor, currency, locale),
            context.getString(R.string.recap_vs_last_month) to
                String.format(locale, "%+.1f%%", report.changePercent),
            context.getString(R.string.recap_year_to_date) to
                Money.format(recap.yearToDateMinor, currency, locale),
        )

        rows.forEach { (label, value) ->
            canvas.drawText(label, MARGIN, layout.y, mutedPaint)
            canvas.drawText(value, MARGIN + 220f, layout.y, bodyPaint)
            layout.y += LINE
        }
        layout.y += 8f
    }

    private fun drawCategoryBreakdown(
        layout: Layout,
        recap: MonthlyRecap,
        currency: String,
        locale: Locale,
    ) {
        val categories = recap.report.byCategory.take(12)
        if (categories.isEmpty()) return

        ensureSpace(layout, 40f + categories.size * 22f)
        section(layout, context.getString(R.string.recap_section_categories))
        val canvas = layout.canvas ?: return

        val maxTotal = categories.maxOf { it.totalMinor }.coerceAtLeast(1)
        val barLeft = MARGIN + 150f
        val barMaxWidth = PAGE_WIDTH - MARGIN - barLeft - 90f

        categories.forEach { spend ->
            val name = spend.category?.let { categoryRepository.displayName(it) }
                ?: context.getString(R.string.category_uncategorized)

            canvas.drawText(name.take(24), MARGIN, layout.y + 8f, bodyPaint)

            barPaint.color = spend.category?.colorArgb?.toInt() ?: COLOR_ACCENT
            val width = (spend.totalMinor.toFloat() / maxTotal * barMaxWidth).coerceAtLeast(2f)
            canvas.drawRoundRect(
                RectF(barLeft, layout.y, barLeft + width, layout.y + 10f),
                3f,
                3f,
                barPaint,
            )

            canvas.drawText(
                Money.format(spend.totalMinor, currency, locale),
                PAGE_WIDTH - MARGIN - 86f,
                layout.y + 8f,
                bodyPaint,
            )
            canvas.drawText(
                String.format(locale, "%.0f%%", spend.shareOfTotal * 100),
                PAGE_WIDTH - MARGIN - 24f,
                layout.y + 8f,
                mutedPaint,
            )
            layout.y += 20f
        }
        layout.y += 10f
    }

    private fun drawBudgets(layout: Layout, recap: MonthlyRecap, currency: String, locale: Locale) {
        if (recap.budgets.isEmpty()) return
        ensureSpace(layout, 40f + recap.budgets.size * 18f)
        section(layout, context.getString(R.string.recap_section_budgets))
        val canvas = layout.canvas ?: return

        recap.budgets.forEach { progress ->
            val name = progress.category?.let { categoryRepository.displayName(it) }
                ?: context.getString(R.string.budget_overall)
            canvas.drawText(name.take(24), MARGIN, layout.y, bodyPaint)
            canvas.drawText(
                context.getString(
                    R.string.recap_budget_line,
                    Money.format(progress.spentMinor, currency, locale),
                    Money.format(progress.limitMinor, currency, locale),
                ),
                MARGIN + 160f,
                layout.y,
                bodyPaint,
            )
            val statusPaint = Paint(bodyPaint).apply {
                color = if (progress.isOverBudget) COLOR_OVER else COLOR_OK
            }
            canvas.drawText(
                String.format(locale, "%.0f%%", progress.usedFraction * 100),
                PAGE_WIDTH - MARGIN - 40f,
                layout.y,
                statusPaint,
            )
            layout.y += LINE
        }
        layout.y += 10f
    }

    private fun drawInsights(layout: Layout, recap: MonthlyRecap) {
        val insights = recap.insights.take(6)
        if (insights.isEmpty()) return
        ensureSpace(layout, 40f + insights.size * 30f)
        section(layout, context.getString(R.string.recap_section_insights))
        val canvas = layout.canvas ?: return

        insights.forEach { insight ->
            ensureSpace(layout, 32f)
            val title = context.getString(insight.titleRes, *insight.titleArgs.toTypedArray())
            val body = context.getString(insight.bodyRes, *insight.bodyArgs.toTypedArray())
            canvas.drawText("• $title", MARGIN, layout.y, headingPaint)
            layout.y += LINE - 3f
            wrapText(body, PAGE_WIDTH - MARGIN * 2 - 12f, mutedPaint).forEach { line ->
                layout.canvas?.drawText(line, MARGIN + 12f, layout.y, mutedPaint)
                layout.y += LINE - 4f
            }
            layout.y += 6f
        }
    }

    private fun drawMerchants(layout: Layout, recap: MonthlyRecap, currency: String, locale: Locale) {
        if (recap.topMerchants.isEmpty()) return
        ensureSpace(layout, 40f + recap.topMerchants.size * 16f)
        section(layout, context.getString(R.string.recap_section_merchants))
        val canvas = layout.canvas ?: return

        recap.topMerchants.forEach { merchant ->
            canvas.drawText(merchant.merchant.take(30), MARGIN, layout.y, bodyPaint)
            canvas.drawText(
                context.getString(R.string.recap_visits, merchant.visits),
                MARGIN + 220f,
                layout.y,
                mutedPaint,
            )
            canvas.drawText(
                Money.format(merchant.totalMinor, currency, locale),
                PAGE_WIDTH - MARGIN - 80f,
                layout.y,
                bodyPaint,
            )
            layout.y += LINE
        }
        layout.y += 10f
    }

    private fun drawSettlements(layout: Layout, recap: MonthlyRecap, locale: Locale) {
        val summaries = recap.settlementSummary.filter {
            it.youAreOwedMinor > 0 || it.youOweMinor > 0 || it.openBills > 0
        }
        if (summaries.isEmpty()) return

        ensureSpace(layout, 40f + summaries.size * 16f)
        section(layout, context.getString(R.string.recap_section_settlements))
        val canvas = layout.canvas ?: return

        summaries.forEach { summary ->
            canvas.drawText(summary.groupName.take(24), MARGIN, layout.y, bodyPaint)
            val detail = when {
                summary.youAreOwedMinor > 0 -> context.getString(
                    R.string.recap_you_are_owed,
                    Money.format(summary.youAreOwedMinor, summary.currency, locale),
                )
                summary.youOweMinor > 0 -> context.getString(
                    R.string.recap_you_owe,
                    Money.format(summary.youOweMinor, summary.currency, locale),
                )
                else -> context.getString(R.string.recap_all_settled)
            }
            canvas.drawText(detail, MARGIN + 160f, layout.y, bodyPaint)
            layout.y += LINE
        }
        layout.y += 10f
    }

    private fun drawExpenseTable(
        layout: Layout,
        expenses: List<Expense>,
        currency: String,
        locale: Locale,
    ) {
        if (expenses.isEmpty()) return
        ensureSpace(layout, 60f)
        section(layout, context.getString(R.string.recap_section_expenses))

        val dateFormat = DateTimeFormatter.ofPattern("dd MMM", locale)
        layout.canvas?.let { canvas ->
            canvas.drawText(context.getString(R.string.export_column_date), MARGIN, layout.y, mutedPaint)
            canvas.drawText(context.getString(R.string.export_column_title), MARGIN + 60f, layout.y, mutedPaint)
            canvas.drawText(
                context.getString(R.string.export_column_category),
                MARGIN + 260f,
                layout.y,
                mutedPaint,
            )
            canvas.drawText(
                context.getString(R.string.export_column_amount),
                PAGE_WIDTH - MARGIN - 70f,
                layout.y,
                mutedPaint,
            )
        }
        layout.y += LINE - 4f

        expenses.sortedByDescending { it.date }.forEach { expense ->
            ensureSpace(layout, LINE)
            val canvas = layout.canvas ?: return
            canvas.drawText(expense.date.format(dateFormat), MARGIN, layout.y, bodyPaint)
            canvas.drawText(expense.title.take(30), MARGIN + 60f, layout.y, bodyPaint)
            canvas.drawText(
                (expense.merchant ?: "").take(20),
                MARGIN + 260f,
                layout.y,
                mutedPaint,
            )
            canvas.drawText(
                Money.format(expense.baseAmountMinor, currency, locale),
                PAGE_WIDTH - MARGIN - 70f,
                layout.y,
                bodyPaint,
            )
            layout.y += LINE - 3f
        }
    }

    private fun section(layout: Layout, title: String) {
        ensureSpace(layout, 30f)
        val canvas = layout.canvas ?: return
        layout.y += 6f
        canvas.drawText(title, MARGIN, layout.y, headingPaint)
        layout.y += 6f
        canvas.drawLine(MARGIN, layout.y, PAGE_WIDTH - MARGIN, layout.y, rulePaint)
        layout.y += 14f
    }

    /** Greedy word wrap; [Paint.measureText] is the only reliable width source for the chosen face. */
    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
