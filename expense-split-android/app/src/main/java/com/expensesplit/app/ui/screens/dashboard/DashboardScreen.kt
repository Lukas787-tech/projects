package com.expensesplit.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.domain.model.BudgetProgress
import com.expensesplit.app.domain.model.GroupSettlementSummary
import com.expensesplit.app.ui.charts.LineChart
import com.expensesplit.app.ui.components.BudgetBar
import com.expensesplit.app.ui.components.DeltaChip
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.ExpenseCard
import com.expensesplit.app.ui.components.InsightCard
import com.expensesplit.app.ui.components.SectionHeader
import com.expensesplit.app.ui.components.StatTile
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.theme.LocalFinanceColors

/**
 * Home screen: this month at a glance, then the things that need attention (budgets, unsettled
 * balances, insights), then recent activity.
 */
@Composable
fun DashboardScreen(
    onExpenseClick: (Long) -> Unit,
    onSeeAllExpenses: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenScanner: () -> Unit,
    onAddExpense: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.isLoading) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            // Room for the FAB so the last card is never trapped underneath it.
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthSummaryCard(state) }

        if (state.dailySeries.size >= 3) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.dashboard_this_month_trend),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LineChart(
                            values = state.dailySeries.map { it.totalMinor.toFloat() },
                            height = 130,
                        )
                    }
                }
            }
        }

        if (state.budgets.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.dashboard_budgets),
                    trailing = {
                        TextButton(onClick = onOpenAnalytics) {
                            Text(stringResource(R.string.action_see_all))
                        }
                    },
                )
            }
            items(state.budgets.take(3), key = { it.budget.id }) { progress ->
                BudgetRow(progress = progress, viewModel = viewModel)
            }
        }

        if (state.settlements.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.dashboard_settlements)) }
            items(state.settlements, key = { it.groupName }) { summary ->
                SettlementRow(summary = summary, onClick = onOpenBills)
            }
        }

        if (state.insights.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.dashboard_insights)) }
            items(state.insights, key = { it.id }) { insight ->
                InsightCard(
                    insight = insight,
                    baseCurrency = state.baseCurrency,
                    onClick = onOpenAnalytics,
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.dashboard_recent),
                trailing = {
                    TextButton(onClick = onSeeAllExpenses) {
                        Text(stringResource(R.string.action_see_all))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
            )
        }

        if (state.recentExpenses.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.ReceiptLong,
                    title = stringResource(R.string.empty_expenses_title),
                    message = stringResource(R.string.empty_expenses_message),
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onAddExpense) {
                                Text(stringResource(R.string.action_add_expense))
                            }
                            TextButton(onClick = onOpenScanner) {
                                Text(stringResource(R.string.action_scan_receipt))
                            }
                        }
                    },
                )
            }
        } else {
            items(state.recentExpenses, key = { it.id }) { expense ->
                val category = state.categories[expense.categoryId]
                ExpenseCard(
                    expense = expense,
                    category = category,
                    categoryName = viewModel.categoryName(category),
                    baseCurrency = state.baseCurrency,
                    onClick = { onExpenseClick(expense.id) },
                )
            }
        }
    }
}

@Composable
private fun MonthSummaryCard(state: DashboardUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.dashboard_spent_this_month),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMoney(state.monthTotalMinor, state.baseCurrency),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                DeltaChip(
                    changePercent = state.monthChangePercent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(
                    label = stringResource(R.string.dashboard_today),
                    value = formatMoney(state.todayTotalMinor, state.baseCurrency),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                StatTile(
                    label = stringResource(R.string.dashboard_this_week),
                    value = formatMoney(state.weekTotalMinor, state.baseCurrency),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                StatTile(
                    label = stringResource(R.string.dashboard_daily_average),
                    value = formatMoney(state.dailyAverageMinor, state.baseCurrency),
                    supporting = stringResource(
                        R.string.dashboard_transaction_count,
                        state.transactionCount,
                    ),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BudgetRow(progress: BudgetProgress, viewModel: DashboardViewModel) {
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
                    text = progress.category?.let { viewModel.categoryName(it) }
                        ?: stringResource(R.string.budget_overall),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        R.string.budget_spent_of_limit,
                        formatMoney(progress.spentMinor, progress.currency),
                        formatMoney(progress.limitMinor, progress.currency),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.isOverBudget) finance.overBudget else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            BudgetBar(
                fraction = progress.usedFraction,
                isOverBudget = progress.isOverBudget,
                isNearLimit = progress.isNearLimit,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (progress.isOverBudget) {
                    stringResource(
                        R.string.budget_over_by,
                        formatMoney(-progress.remainingMinor, progress.currency),
                    )
                } else {
                    stringResource(
                        R.string.budget_safe_daily,
                        formatMoney(progress.safeDailyMinor, progress.currency),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettlementRow(summary: GroupSettlementSummary, onClick: () -> Unit) {
    val finance = LocalFinanceColors.current
    val isOwed = summary.youAreOwedMinor > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = summary.groupName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.bills_open_count, summary.openBills),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(
                        if (isOwed) R.string.bills_you_are_owed else R.string.bills_you_owe,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMoney(
                        if (isOwed) summary.youAreOwedMinor else summary.youOweMinor,
                        summary.currency,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isOwed) finance.positive else finance.negative,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
