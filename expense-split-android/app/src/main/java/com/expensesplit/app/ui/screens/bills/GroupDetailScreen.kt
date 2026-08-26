package com.expensesplit.app.ui.screens.bills

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.core.Money
import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.MemberBalance
import com.expensesplit.app.domain.model.SettlementSuggestion
import com.expensesplit.app.domain.model.SplitMethod
import com.expensesplit.app.domain.split.SplitCalculator
import com.expensesplit.app.ui.components.ChipRow
import com.expensesplit.app.ui.components.DateField
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.MemberAvatar
import com.expensesplit.app.ui.components.formatDateShort
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.theme.LocalFinanceColors

/** Group detail: the bills, where everyone stands, and the shortest way to square up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val exportedFile by viewModel.exportedFile.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var showBillSheet by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                title = { Text(state.group?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMember = true }) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = stringResource(R.string.action_add_member),
                        )
                    }
                    IconButton(
                        onClick = {
                            context.startActivity(
                                viewModel.shareTextIntent(viewModel.shareSummaryText())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = viewModel::exportSummary) {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = stringResource(R.string.action_export_csv),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startNewBill()
                    showBillSheet = true
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_bill))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf(
                    R.string.tab_bills,
                    R.string.tab_balances,
                    R.string.tab_settle_up,
                ).forEachIndexed { index, labelRes ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(labelRes)) },
                    )
                }
            }

            when (selectedTab) {
                0 -> BillsTab(
                    bills = state.bills,
                    memberName = state::memberName,
                    currency = state.currency,
                    onEdit = { billId ->
                        viewModel.startEditingBill(billId)
                        showBillSheet = true
                    },
                    onDelete = viewModel::deleteBill,
                )

                1 -> BalancesTab(
                    balances = state.balances,
                    currency = state.currency,
                    onRemoveMember = viewModel::removeMember,
                )

                else -> SettleTab(
                    plan = state.settlementPlan,
                    memberName = state::memberName,
                    currency = state.currency,
                    isSettledUp = state.isSettledUp,
                    onRecord = viewModel::recordSettlement,
                )
            }
        }
    }

    if (showBillSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBillSheet = false },
            sheetState = sheetState,
        ) {
            BillEditor(
                state = state,
                draft = draft,
                viewModel = viewModel,
                onSave = {
                    viewModel.saveBill()
                    showBillSheet = false
                },
            )
        }
    }

    if (showAddMember) {
        var newMemberName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddMember = false },
            title = { Text(stringResource(R.string.action_add_member)) },
            text = {
                OutlinedTextField(
                    value = newMemberName,
                    onValueChange = { newMemberName = it },
                    label = { Text(stringResource(R.string.field_member_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addMember(newMemberName)
                        showAddMember = false
                    },
                    enabled = newMemberName.isNotBlank(),
                ) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddMember = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BillsTab(
    bills: List<Bill>,
    memberName: (Long) -> String,
    currency: String,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (bills.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Receipt,
            title = stringResource(R.string.empty_bills_title),
            message = stringResource(R.string.empty_bills_message),
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(bills, key = { it.id }) { bill ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEdit(bill.id) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = bill.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(
                                R.string.bill_paid_by,
                                memberName(bill.paidByMemberId),
                                formatDateShort(bill.date),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatMoney(bill.totalMinor, currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { onDelete(bill.id) }) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BalancesTab(
    balances: List<MemberBalance>,
    currency: String,
    onRemoveMember: (Long) -> Unit,
) {
    val finance = LocalFinanceColors.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(balances, key = { it.member.id }) { balance ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(
                        name = balance.member.name,
                        colorArgb = balance.member.avatarColorArgb,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = balance.member.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (balance.member.isSelf) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = stringResource(
                                R.string.balance_paid_owed,
                                formatMoney(balance.paidMinor, currency),
                                formatMoney(balance.owedMinor, currency),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatMoney(balance.netMinor, currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            balance.netMinor > 0 -> finance.positive
                            balance.netMinor < 0 -> finance.negative
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettleTab(
    plan: List<SettlementSuggestion>,
    memberName: (Long) -> String,
    currency: String,
    isSettledUp: Boolean,
    onRecord: (SettlementSuggestion) -> Unit,
) {
    if (plan.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Check,
            title = stringResource(
                if (isSettledUp) R.string.settle_all_done_title else R.string.settle_nothing_title,
            ),
            message = stringResource(R.string.settle_all_done_message),
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settle_plan_explainer, plan.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        items(plan, key = { "${it.fromMemberId}-${it.toMemberId}-${it.amountMinor}" }) { suggestion ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = memberName(suggestion.fromMemberId),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Text(
                            text = memberName(suggestion.toMemberId),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatMoney(suggestion.amountMinor, currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onRecord(suggestion) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_mark_as_paid))
                    }
                }
            }
        }
    }
}

/**
 * Add/edit bill sheet.
 *
 * The per-member share list under the split controls updates live, so the user sees the exact
 * amounts — rounding included — before committing.
 */
@Composable
private fun BillEditor(
    state: GroupDetailUiState,
    draft: BillDraft,
    viewModel: GroupDetailViewModel,
    onSave: () -> Unit,
) {
    val currency = state.currency
    val totalMinor = Money.parseToMinor(draft.totalText, currency) ?: 0L
    val allocated = draft.computedShares.values.sum()
    val remainder = totalMinor - allocated

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = stringResource(
                if (draft.editingBillId == 0L) R.string.title_add_bill else R.string.title_edit_bill,
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = draft.title,
            onValueChange = viewModel::onTitleChanged,
            label = { Text(stringResource(R.string.field_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.totalText,
            onValueChange = viewModel::onTotalChanged,
            label = { Text(stringResource(R.string.field_total)) },
            singleLine = true,
            suffix = { Text(currency) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.field_paid_by),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = state.members,
            selected = state.members.firstOrNull { it.id == draft.paidByMemberId },
            optionLabel = { it.name },
            onSelected = { viewModel.onPaidByChanged(it.id) },
        )

        Spacer(Modifier.height(12.dp))
        DateField(date = draft.date, onDateChange = viewModel::onDateChanged)

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.field_split_method),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = SplitMethod.entries,
            selected = draft.splitMethod,
            optionLabel = { stringResource(it.labelRes) },
            onSelected = viewModel::onSplitMethodChanged,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        state.members.forEach { member ->
            val included = member.id in draft.includedMemberIds
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = included,
                    onClick = { viewModel.toggleMember(member.id) },
                    label = { Text(member.name) },
                )
                Spacer(Modifier.width(10.dp))

                if (included && draft.splitMethod != SplitMethod.EQUAL) {
                    OutlinedTextField(
                        value = draft.weights[member.id].orEmpty(),
                        onValueChange = { viewModel.onWeightChanged(member.id, it) },
                        label = {
                            Text(
                                stringResource(
                                    when (draft.splitMethod) {
                                        SplitMethod.PERCENTAGE -> R.string.field_percent
                                        SplitMethod.SHARES -> R.string.field_shares
                                        else -> R.string.field_amount
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }

                Text(
                    text = draft.computedShares[member.id]
                        ?.let { formatMoney(it, currency) }
                        .orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }

        if (draft.warnings.isNotEmpty() || remainder != 0L) {
            Spacer(Modifier.height(8.dp))
            draft.warnings.distinct().forEach { warning ->
                Text(
                    text = stringResource(warning.messageRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = draft.note,
            onValueChange = viewModel::onNoteChanged,
            label = { Text(stringResource(R.string.field_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSave,
            enabled = draft.title.isNotBlank() && totalMinor > 0 && draft.includedMemberIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save_bill))
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun SplitCalculator.Warning.messageRes(): Int = when (this) {
    SplitCalculator.Warning.PERCENTAGES_RESCALED -> R.string.split_warning_percentages
    SplitCalculator.Warning.CUSTOM_AMOUNTS_ADJUSTED -> R.string.split_warning_custom
    SplitCalculator.Warning.INVALID_WEIGHT_IGNORED -> R.string.split_warning_invalid_weight
}
