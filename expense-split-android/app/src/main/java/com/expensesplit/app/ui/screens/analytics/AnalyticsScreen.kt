package com.expensesplit.app.ui.screens.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.core.Money
import com.expensesplit.app.domain.model.BudgetPeriod
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.SpendingReport
import com.expensesplit.app.domain.model.TrendDirection
import com.expensesplit.app.ui.charts.BarChart
import com.expensesplit.app.ui.charts.BarEntry
import com.expensesplit.app.ui.charts.DonutChart
import com.expensesplit.app.ui.charts.DonutSlice
import com.expensesplit.app.ui.charts.LineChart
import com.expensesplit.app.ui.components.BudgetBar
import com.expensesplit.app.ui.components.ChipRow
import com.expensesplit.app.ui.components.DeltaChip
import com.expensesplit.app.ui.components.DropdownField
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.InsightCard
import com.expensesplit.app.ui.components.LegendDot
import com.expensesplit.app.ui.components.SectionHeader
import com.expensesplit.app.ui.components.StatTile
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.components.formatMoneyCompact
import com.expensesplit.app.ui.components.formatPercent
import com.expensesplit.app.ui.theme.ChartPalette
import com.expensesplit.app.ui.theme.LocalFinanceColors
import java.time.format.TextStyle
import java.util.Locale

/** Charts, trends, budget tracking and the full insight list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onOpenRecap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showBudgetDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_analytics)) },
                actions = {
                    IconButton(onClick = onOpenRecap) {
                        Icon(
                            Icons.Filled.Summarize,
                            contentDescription = stringResource(R.string.title_recap),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report

        if (report == null || report.transactionCount == 0) {
            EmptyState(
                icon = Icons.Filled.Insights,
                title = stringResource(R.string.empty_analytics_title),
                message = stringResource(R.string.empty_analytics_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalyticsPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = state.period == period,
                            onClick = { viewModel.onPeriodChanged(period) },
                            label = { Text(stringResource(period.labelRes)) },
                        )
                    }
                }
            }

            item { SummaryCard(report = report, currency = state.baseCurrency) }

            item {
                CategoryBreakdownCard(
                    report = report,
                    currency = state.baseCurrency,
                    nameOf = viewModel::categoryName,
                )
            }

            item { TrendCard(state = state) }

            if (state.monthlySeries.isNotEmpty()) {
                item { MonthlyComparisonCard(state = state) }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.analytics_budgets),
                    trailing = {
                        TextButton(onClick = { showBudgetDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.action_set_budget))
                        }
                    },
                )
            }

            if (state.budgets.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.analytics_no_budgets),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.budgets, key = { it.budget.id }) { progress ->
                    BudgetCard(
                        progress = progress,
                        nameOf = viewModel::categoryName,
                        onDelete = { viewModel.deleteBudget(progress.budget.id) },
                    )
                }
            }

            if (state.insights.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.analytics_tips)) }
                items(state.insights, key = { it.id }) { insight ->
                    InsightCard(insight = insight, baseCurrency = state.baseCurrency)
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showBudgetDialog) {
        BudgetDialog(
            categories = state.categories,
            nameOf = viewModel::categoryName,
            currency = state.baseCurrency,
            onDismiss = { showBudgetDialog = false },
            onSave = { categoryId, limitMinor, period ->
                showBudgetDialog = false
                viewModel.saveBudget(categoryId, limitMinor, state.baseCurrency, period)
            },
        )
    }
}

@Composable
private fun SummaryCard(report: SpendingReport, currency: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMoney(report.totalMinor, currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                DeltaChip(changePercent = report.changePercent, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(
                    label = stringResource(R.string.analytics_transactions),
                    value = report.transactionCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.analytics_per_day),
                    value = formatMoney(report.averagePerDayMinor, currency),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.analytics_per_transaction),
                    value = formatMoney(report.averagePerTransactionMinor, currency),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(
    report: SpendingReport,
    currency: String,
    nameOf: (com.expensesplit.app.domain.model.Category?) -> String,
) {
    val top = report.byCategory.take(8)
    val slices = top.mapIndexed { index, spend ->
        DonutSlice(
            value = spend.totalMinor.toFloat(),
            color = spend.category?.colorArgb?.let { Color(it) } ?: ChartPalette[index % ChartPalette.size],
            label = nameOf(spend.category),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.analytics_by_category),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(slices = slices, diameter = 156) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatMoneyCompact(report.totalMinor, currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.analytics_total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.padding(horizontal = 8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    top.forEachIndexed { index, spend ->
                        LegendDot(
                            color = spend.category?.colorArgb?.let { Color(it) }
                                ?: ChartPalette[index % ChartPalette.size],
                            label = nameOf(spend.category),
                            value = formatPercent(spend.shareOfTotal * 100),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            top.forEach { spend ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = nameOf(spend.category),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = formatMoney(spend.totalMinor, currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendCard(state: AnalyticsUiState) {
    val report = state.report ?: return
    val trend = state.trend ?: return
    val finance = LocalFinanceColors.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.analytics_trend),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            val actual = report.byDay.map { it.totalMinor.toFloat() }
            // Flat projection line at the fitted daily rate; the dashed style marks it as an estimate.
            val projectionDaily = if (state.period.projectionDays > 0) {
                trend.projectedNextPeriodMinor.toFloat() / state.period.projectionDays
            } else {
                0f
            }
            val projection = List(minOf(state.period.projectionDays, 14)) { projectionDaily }

            LineChart(values = actual, projection = projection, height = 170)

            Spacer(Modifier.height(12.dp))

            Text(
                text = when {
                    !state.trendIsMeaningful -> stringResource(R.string.trend_unclear)
                    state.trendDirection == TrendDirection.RISING -> stringResource(
                        R.string.trend_rising,
                        formatMoney(trend.projectedNextPeriodMinor, state.baseCurrency),
                    )
                    state.trendDirection == TrendDirection.FALLING -> stringResource(
                        R.string.trend_falling,
                        formatMoney(trend.projectedNextPeriodMinor, state.baseCurrency),
                    )
                    else -> stringResource(R.string.trend_stable)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.trendDirection) {
                    TrendDirection.RISING -> finance.negative
                    TrendDirection.FALLING -> finance.positive
                    TrendDirection.STABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (state.trendIsMeaningful) {
                Text(
                    text = stringResource(
                        R.string.trend_confidence,
                        (trend.confidence * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthlyComparisonCard(state: AnalyticsUiState) {
    val locale = Locale.getDefault()
    val entries = state.monthlySeries.map { spend ->
        BarEntry(
            label = spend.month.month.getDisplayName(TextStyle.SHORT, locale),
            value = spend.totalMinor.toFloat(),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.analytics_month_over_month),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(14.dp))
            BarChart(
                entries = entries,
                height = 150,
                highlightIndex = entries.lastIndex,
            )
        }
    }
}

@Composable
private fun BudgetCard(
    progress: BudgetProgress,
    nameOf: (com.expensesplit.app.domain.model.Category?) -> String,
    onDelete: () -> Unit,
) {
    val finance = LocalFinanceColors.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = progress.category?.let { nameOf(it) }
                        ?: stringResource(R.string.budget_overall),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_remove))
                }
            }

            BudgetBar(
                fraction = progress.usedFraction,
                isOverBudget = progress.isOverBudget,
                isNearLimit = progress.isNearLimit,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.budget_spent_of_limit,
                        formatMoney(progress.spentMinor, progress.currency),
                        formatMoney(progress.limitMinor, progress.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.budget_projected,
                        formatMoney(progress.projectedSpendMinor, progress.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.projectedSpendMinor > progress.limitMinor) {
                        finance.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun BudgetDialog(
    categories: List<com.expensesplit.app.domain.model.Category>,
    nameOf: (com.expensesplit.app.domain.model.Category?) -> String,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (Long?, Long, BudgetPeriod) -> Unit,
) {
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var limitText by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(BudgetPeriod.MONTHLY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_set_budget)) },
        text = {
            Column {
                DropdownField(
                    label = stringResource(R.string.field_category),
                    options = listOf<Long?>(null) + categories.map { it.id },
                    selected = selectedCategoryId,
                    optionLabel = { id ->
                        id?.let { categoryId -> nameOf(categories.firstOrNull { it.id == categoryId }) }
                            ?: stringResource(R.string.budget_overall)
                    },
                    onSelected = { selectedCategoryId = it },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { limitText = it },
                    label = { Text(stringResource(R.string.field_limit)) },
                    suffix = { Text(currency) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ChipRow(
                    options = BudgetPeriod.entries,
                    selected = period,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelected = { period = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = Money.parseToMinor(limitText, currency) ?: return@Button
                    if (limit > 0) onSave(selectedCategoryId, limit, period)
                },
                enabled = limitText.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
