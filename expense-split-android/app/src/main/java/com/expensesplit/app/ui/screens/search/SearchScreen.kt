package com.expensesplit.app.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.ExpenseCard
import com.expensesplit.app.ui.components.formatDateShort
import com.expensesplit.app.ui.components.formatMoney

/**
 * Advanced search across expenses and — on the second tab — the individual items on every scanned
 * receipt, which is what answers "when did I last buy this, and what did it cost?".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onExpenseClick: (Long) -> Unit,
    onReceiptClick: (Long) -> Unit,
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val itemResults by viewModel.itemResults.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showFilters by remember { mutableStateOf(false) }
    var minAmount by remember { mutableStateOf("") }
    var maxAmount by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_search)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.filters.activeFilterCount > 0) {
                        TextButton(onClick = viewModel::clearAll) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filters.keyword,
                onValueChange = viewModel::onKeywordChanged,
                label = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.filters.keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onKeywordChanged("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(stringResource(R.string.search_tab_expenses, state.results.size))
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(stringResource(R.string.search_tab_items, itemResults.size))
                    },
                )
            }

            if (selectedTab == 0) {
                TextButton(
                    onClick = { showFilters = !showFilters },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (showFilters) R.string.action_hide_filters else R.string.action_show_filters,
                            state.filters.activeFilterCount,
                        ),
                    )
                }

                if (showFilters) {
                    FilterPanel(
                        state = state,
                        viewModel = viewModel,
                        minAmount = minAmount,
                        maxAmount = maxAmount,
                        onMinAmountChange = {
                            minAmount = it
                            viewModel.onAmountRangeChanged(it, maxAmount, state.baseCurrency)
                        },
                        onMaxAmountChange = {
                            maxAmount = it
                            viewModel.onAmountRangeChanged(minAmount, it, state.baseCurrency)
                        },
                    )
                }

                if (state.results.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.empty_search_title),
                        message = stringResource(R.string.empty_search_message),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.list_result_count, state.results.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMoney(state.totalMinor, state.baseCurrency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.results, key = { it.id }) { expense ->
                            val category = state.categoriesById[expense.categoryId]
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
            } else {
                if (itemResults.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.empty_items_title),
                        message = stringResource(R.string.empty_items_message),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(itemResults, key = { it.itemId }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onItemClick(item.normalizedName) },
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.item_bought_at,
                                                item.merchant ?: stringResource(R.string.unknown_store),
                                                formatDateShort(item.purchasedAt),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = formatMoney(item.totalPriceMinor, item.currency),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (item.quantity > 1.0) {
                                            Text(
                                                text = stringResource(
                                                    R.string.receipt_item_quantity,
                                                    item.quantity.toInt(),
                                                    formatMoney(item.unitPriceMinor, item.currency),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    TextButton(onClick = { onReceiptClick(item.receiptId) }) {
                                        Text(stringResource(R.string.action_view_receipt))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    state: SearchUiState,
    viewModel: SearchViewModel,
    minAmount: String,
    maxAmount: String,
    onMinAmountChange: (String) -> Unit,
    onMaxAmountChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.filter_period),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickSearchRange.entries.forEach { range ->
                FilterChip(
                    selected = state.filters.range == range.toRange(),
                    onClick = { viewModel.onQuickRange(range) },
                    label = { Text(stringResource(range.labelRes)) },
                )
            }
            FilterChip(
                selected = state.filters.range == null,
                onClick = { viewModel.onRangeChanged(null) },
                label = { Text(stringResource(R.string.filter_all_time)) },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.filter_categories),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEach { category ->
                FilterChip(
                    selected = category.id in state.filters.categoryIds,
                    onClick = { viewModel.toggleCategory(category.id) },
                    label = { Text(viewModel.categoryName(category)) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.filter_payment_methods),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentMethod.entries.forEach { method ->
                FilterChip(
                    selected = method in state.filters.paymentMethods,
                    onClick = { viewModel.togglePaymentMethod(method) },
                    label = { Text(stringResource(method.labelRes)) },
                )
            }
        }

        if (state.groups.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.filter_groups),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.groups.forEach { group ->
                    FilterChip(
                        selected = group.id in state.filters.groupIds,
                        onClick = { viewModel.toggleGroup(group.id) },
                        label = { Text(group.name) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = minAmount,
                onValueChange = onMinAmountChange,
                label = { Text(stringResource(R.string.filter_min_amount)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = maxAmount,
                onValueChange = onMaxAmountChange,
                label = { Text(stringResource(R.string.filter_max_amount)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.filters.withReceiptOnly,
                onClick = { viewModel.onReceiptOnlyChanged(!state.filters.withReceiptOnly) },
                label = { Text(stringResource(R.string.filter_with_receipt)) },
            )
            FilterChip(
                selected = state.filters.settledOnly == true,
                onClick = {
                    viewModel.onSettledFilterChanged(
                        if (state.filters.settledOnly == true) null else true,
                    )
                },
                label = { Text(stringResource(R.string.filter_settled)) },
            )
            FilterChip(
                selected = state.filters.settledOnly == false,
                onClick = {
                    viewModel.onSettledFilterChanged(
                        if (state.filters.settledOnly == false) null else false,
                    )
                },
                label = { Text(stringResource(R.string.filter_unsettled)) },
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}
