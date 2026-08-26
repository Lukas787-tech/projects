package com.expensesplit.app.ui.screens.receipt

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.expensesplit.app.R
import com.expensesplit.app.ui.charts.LineChart
import com.expensesplit.app.ui.components.ConfirmDialog
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.SectionHeader
import com.expensesplit.app.ui.components.formatDate
import com.expensesplit.app.ui.components.formatDateShort
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.components.formatPercent
import com.expensesplit.app.ui.theme.LocalFinanceColors

/** Grid of stored receipt photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptGalleryScreen(
    onReceiptClick: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptGalleryViewModel = hiltViewModel(),
) {
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_receipts)) },
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
        if (receipts.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.PhotoLibrary,
                title = stringResource(R.string.empty_receipts_title),
                message = stringResource(R.string.empty_receipts_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(receipts, key = { it.id }) { receipt ->
                Card(
                    onClick = { onReceiptClick(receipt.id) },
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column {
                        if (!receipt.imageUri.isNullOrBlank()) {
                            AsyncImage(
                                model = receipt.imageUri,
                                contentDescription = receipt.merchant,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.78f)
                                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = receipt.merchant
                                    ?: stringResource(R.string.unknown_store),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                text = "${formatDateShort(receipt.purchasedAt)} · " +
                                    formatMoney(receipt.totalMinor, receipt.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One receipt: the image, every line item, and what those items cost elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val finance = LocalFinanceColors.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.receipt?.merchant ?: stringResource(R.string.title_receipt)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshOffers) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh_offers),
                        )
                    }
                    state.receipt?.imageUri?.let { uri ->
                        IconButton(
                            onClick = {
                                context.startActivity(
                                    viewModel.shareImageIntent(
                                        uri,
                                        state.receipt?.merchant.orEmpty(),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val receipt = state.receipt ?: return@Scaffold

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            if (!receipt.imageUri.isNullOrBlank()) {
                item {
                    AsyncImage(
                        model = receipt.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DetailRow(
                            stringResource(R.string.field_merchant),
                            receipt.merchant ?: stringResource(R.string.unknown_store),
                        )
                        DetailRow(
                            stringResource(R.string.field_date),
                            formatDate(receipt.purchasedAt),
                        )
                        DetailRow(
                            stringResource(R.string.field_total),
                            formatMoney(receipt.totalMinor, receipt.currency),
                        )
                        if (receipt.taxMinor > 0) {
                            DetailRow(
                                stringResource(R.string.field_tax),
                                formatMoney(receipt.taxMinor, receipt.currency),
                            )
                        }
                        DetailRow(
                            stringResource(R.string.receipt_scan_confidence),
                            formatPercent(receipt.scanConfidence * 100),
                        )
                    }
                }
            }

            if (state.savings.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = finance.positive.copy(alpha = 0.1f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Savings,
                                    contentDescription = null,
                                    tint = finance.positive,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(
                                        R.string.receipt_savings_title,
                                        formatMoney(state.totalSavingMinor, receipt.currency),
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            state.savings.forEach { saving ->
                                Text(
                                    text = stringResource(
                                        R.string.receipt_saving_line,
                                        saving.displayName,
                                        saving.bestStore,
                                        formatMoney(saving.bestMinor, saving.currency),
                                        formatMoney(saving.savingMinor, saving.currency),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.receipt_items, state.items.size)) }

            items(state.items, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
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
                    Text(
                        text = formatMoney(item.totalPriceMinor, item.currency),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = { onItemClick(item.normalizedName) }) {
                        Icon(
                            Icons.Filled.ShowChart,
                            contentDescription = stringResource(R.string.action_price_history),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (state.repeatPurchases.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.receipt_repeat_purchases)) }
                items(state.repeatPurchases, key = { it.normalizedItemName }) { duplicate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = duplicate.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(
                                R.string.receipt_bought_times,
                                duplicate.occurrences,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_receipt_title),
            message = stringResource(R.string.dialog_delete_receipt_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete(onBack)
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

/** Price history for a single item, with the cheapest and dearest observations called out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriceHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PriceHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finance = LocalFinanceColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.history?.displayName ?: stringResource(R.string.title_price_history)) },
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
        val history = state.history

        if (history == null || history.points.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.ShowChart,
                title = stringResource(R.string.empty_price_history_title),
                message = stringResource(R.string.empty_price_history_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.price_history_chart_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(14.dp))
                        LineChart(
                            values = history.points.map { it.unitPriceMinor.toFloat() },
                            height = 170,
                        )
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            history.cheapest?.let { cheapest ->
                                Column {
                                    Text(
                                        text = stringResource(R.string.price_lowest),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = formatMoney(cheapest.unitPriceMinor, history.currency),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = finance.positive,
                                    )
                                    Text(
                                        text = cheapest.storeName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.price_average),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = formatMoney(history.averageMinor, history.currency),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.price_observations,
                                        history.points.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            state.savings?.let { saving ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = finance.positive.copy(alpha = 0.1f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Savings,
                                contentDescription = null,
                                tint = finance.positive,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(
                                    R.string.price_cheaper_at,
                                    saving.bestStore,
                                    formatMoney(saving.savingMinor, saving.currency),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.price_all_observations)) }

            items(history.points.reversed(), key = { it.id }) { point ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = point.storeName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = formatDate(point.observedOn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatMoney(point.unitPriceMinor, point.currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (point.unitPriceMinor) {
                            history.cheapest?.unitPriceMinor -> finance.positive
                            history.dearest?.unitPriceMinor -> finance.negative
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
