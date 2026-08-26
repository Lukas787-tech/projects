package com.expensesplit.app.ui.screens.addexpense

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.domain.model.RecurrenceFrequency
import com.expensesplit.app.ui.components.AmountField
import com.expensesplit.app.ui.components.BottomSpacer
import com.expensesplit.app.ui.components.CategoryAvatar
import com.expensesplit.app.ui.components.CategoryIcons
import com.expensesplit.app.ui.components.ChipRow
import com.expensesplit.app.ui.components.ConfirmDialog
import com.expensesplit.app.ui.components.DateField
import com.expensesplit.app.ui.components.DropdownField
import com.expensesplit.app.ui.components.FormSection

/**
 * The add/edit form.
 *
 * Amount comes first and largest, because that is the one field every entry needs; everything else
 * has a sensible default so a complete expense can be saved in two taps and a number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseScreen(
    onDone: () -> Unit,
    onOpenScanner: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set when returning from the scanner, so the form can prefill from what was scanned. */
    scannedReceiptId: Long? = null,
    viewModel: AddExpenseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.onAttachmentChanged(it.toString()) } }

    LaunchedEffect(scannedReceiptId) {
        scannedReceiptId?.takeIf { it != 0L }?.let(viewModel::applyScannedReceipt)
    }

    LaunchedEffect(state.savedExpenseId) {
        if (state.savedExpenseId != null) onDone()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.title_edit_expense else R.string.title_add_expense,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            AmountField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChanged,
                currency = state.currency,
                currencies = state.currencies,
                onCurrencyChange = viewModel::onCurrencyChanged,
                isError = state.errors.any { it != ExpenseFormError.TITLE_MISSING },
                errorMessage = amountErrorMessage(state.errors),
                convertedPreview = state.convertedPreview,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenScanner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_scan_receipt))
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChanged,
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true,
                isError = ExpenseFormError.TITLE_MISSING in state.errors,
                supportingText = {
                    if (ExpenseFormError.TITLE_MISSING in state.errors) {
                        Text(stringResource(R.string.error_title_required))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.merchant,
                onValueChange = viewModel::onMerchantChanged,
                label = { Text(stringResource(R.string.field_merchant)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            FormSection(title = stringResource(R.string.field_category)) {
                Column {
                    if (state.categoryWasSuggested) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.category_auto_suggested),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    CategoryGrid(
                        categories = state.categories,
                        selectedId = state.categoryId,
                        nameOf = viewModel::categoryName,
                        onSelected = viewModel::onCategorySelected,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            DateField(date = state.date, onDateChange = viewModel::onDateChanged)

            Spacer(Modifier.height(12.dp))

            FormSection(title = stringResource(R.string.field_payment_method)) {
                ChipRow(
                    options = PaymentMethod.entries,
                    selected = state.paymentMethod,
                    optionLabel = { stringResource(it.labelRes) },
                    onSelected = viewModel::onPaymentMethodChanged,
                )
            }

            if (state.groups.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    label = stringResource(R.string.field_group),
                    options = listOf<Long?>(null) + state.groups.map { it.id },
                    selected = state.groupId,
                    optionLabel = { id ->
                        id?.let { groupId -> state.groups.firstOrNull { it.id == groupId }?.name }
                            ?: stringResource(R.string.field_group_none)
                    },
                    onSelected = viewModel::onGroupChanged,
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChanged,
                label = { Text(stringResource(R.string.field_note)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    attachmentLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (state.attachmentUri != null) {
                            R.string.action_attachment_replace
                        } else {
                            R.string.action_attachment_add
                        },
                    ),
                )
            }

            if (!state.isEditing) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.field_recurring),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.field_recurring_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.isRecurring,
                        onCheckedChange = viewModel::onRecurringToggled,
                    )
                }

                if (state.isRecurring) {
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        options = RecurrenceFrequency.entries,
                        selected = state.recurrenceFrequency,
                        optionLabel = { stringResource(it.labelRes) },
                        onSelected = { viewModel.onRecurrenceChanged(it, state.recurrenceInterval) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        stringResource(
                            if (state.isEditing) R.string.action_save_changes else R.string.action_save_expense,
                        ),
                    )
                }
            }

            BottomSpacer()
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_expense_title),
            message = stringResource(R.string.dialog_delete_expense_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun amountErrorMessage(errors: Set<ExpenseFormError>): String? = when {
    ExpenseFormError.AMOUNT_MISSING in errors -> stringResource(R.string.error_amount_required)
    ExpenseFormError.AMOUNT_INVALID in errors -> stringResource(R.string.error_amount_invalid)
    ExpenseFormError.AMOUNT_ZERO in errors -> stringResource(R.string.error_amount_positive)
    else -> null
}

/** Horizontally scrolling category picker; the selected one is filled, the rest outlined. */
@Composable
private fun CategoryGrid(
    categories: List<com.expensesplit.app.domain.model.Category>,
    selectedId: Long,
    nameOf: (com.expensesplit.app.domain.model.Category) -> String,
    onSelected: (Long) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.lazy.items(categories, key = { it.id }) { category ->
            val selected = category.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(72.dp)
                    .padding(vertical = 4.dp)
                    .clickable { onSelected(category.id) },
            ) {
                CategoryAvatar(
                    icon = CategoryIcons[category.iconKey],
                    colorArgb = if (selected) category.colorArgb else 0xFF9E9E9E,
                    size = if (selected) 52 else 46,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = nameOf(category),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
