package com.expensesplit.app.ui.screens.recap

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.ui.charts.BarChart
import com.expensesplit.app.ui.charts.BarEntry
import com.expensesplit.app.ui.components.BudgetBar
import com.expensesplit.app.ui.components.DeltaChip
import com.expensesplit.app.ui.components.InsightCard
import com.expensesplit.app.ui.components.SectionHeader
import com.expensesplit.app.ui.components.StatTile
import com.expensesplit.app.ui.components.formatMonthYear
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.theme.ChartPalette
import com.expensesplit.app.ui.theme.LocalFinanceColors

/** The end-of-month report, browsable month by month and exportable as PDF or CSV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportedFile by viewModel.exportedFile.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(exportedFile) {
        exportedFile?.let { file ->
            context.startActivity(
                viewModel.shareIntentFor(file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            viewModel.onExportHandled()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_recap)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val recap = state.recap

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(
                            Icons.Filled.ChevronLeft,
                            contentDescription = stringResource(R.string.action_previous_month),
                        )
                    }
                    Text(
                        text = formatMonthYear(state.month),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = viewModel::nextMonth, enabled = state.canGoForward) {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = stringResource(R.string.action_next_month),
                        )
                    }
                }
            }

            if (state.isLoading || recap == null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                return@LazyColumn
            }

            item { HeadlineCard(recap = recap) }

            if (recap.report.byCategory.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.recap_section_categories),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(14.dp))
                            BarChart(
                                entries = recap.report.byCategory.take(6).mapIndexed { index, spend ->
                                    BarEntry(
                                        label = viewModel.categoryName(spend.category).take(6),
                                        value = spend.totalMinor.toFloat(),
                                        color = spend.category?.colorArgb?.let { Color(it) }
                                            ?: ChartPalette[index % ChartPalette.size],
                                    )
                                },
                                height = 150,
                            )
                            Spacer(Modifier.height(12.dp))
                            recap.report.byCategory.take(6).forEach { spend ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = viewModel.categoryName(spend.category),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = formatMoney(spend.totalMinor, recap.currency),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (recap.budgets.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recap_section_budgets)) }
                items(recap.budgets, key = { it.budget.id }) { progress ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = progress.category?.let { viewModel.categoryName(it) }
                                        ?: stringResource(R.string.budget_overall),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.budget_spent_of_limit,
                                        formatMoney(progress.spentMinor, progress.currency),
                                        formatMoney(progress.limitMinor, progress.currency),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            BudgetBar(
                                fraction = progress.usedFraction,
                                isOverBudget = progress.isOverBudget,
                                isNearLimit = progress.isNearLimit,
                            )
                        }
                    }
                }
            }

            if (recap.topMerchants.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recap_section_merchants)) }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            recap.topMerchants.forEachIndexed { index, merchant ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column {
                                        Text(
                                            text = merchant.merchant,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.recap_visits,
                                                merchant.visits,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = formatMoney(merchant.totalMinor, recap.currency),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                if (index < recap.topMerchants.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            val settlements = recap.settlementSummary.filter {
                it.youAreOwedMinor > 0 || it.youOweMinor > 0
            }
            if (settlements.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recap_section_settlements)) }
                items(settlements, key = { it.groupName }) { summary ->
                    val finance = LocalFinanceColors.current
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = summary.groupName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (summary.youAreOwedMinor > 0) {
                                    formatMoney(summary.youAreOwedMinor, summary.currency)
                                } else {
                                    formatMoney(-summary.youOweMinor, summary.currency)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (summary.youAreOwedMinor > 0) {
                                    finance.positive
                                } else {
                                    finance.negative
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            if (recap.insights.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recap_section_insights)) }
                items(recap.insights.take(6), key = { it.id }) { insight ->
                    InsightCard(insight = insight, baseCurrency = recap.currency)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::exportPdf,
                        enabled = !state.isExporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.action_export_pdf))
                    }
                    OutlinedButton(
                        onClick = viewModel::exportCsv,
                        enabled = !state.isExporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.TableChart, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.action_export_csv))
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun HeadlineCard(recap: com.expensesplit.app.domain.model.MonthlyRecap) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.recap_total_spent),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatMoney(recap.report.totalMinor, recap.currency),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                DeltaChip(
                    changePercent = recap.report.changePercent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile(
                    label = stringResource(R.string.recap_transactions),
                    value = recap.report.transactionCount.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                StatTile(
                    label = stringResource(R.string.recap_daily_average),
                    value = formatMoney(recap.report.averagePerDayMinor, recap.currency),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                StatTile(
                    label = stringResource(R.string.recap_year_to_date),
                    value = formatMoney(recap.yearToDateMinor, recap.currency),
                    supporting = stringResource(
                        R.string.recap_ytd_transactions,
                        recap.yearToDateTransactionCount,
                    ),
                    modifier = Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
