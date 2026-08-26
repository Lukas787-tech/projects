package com.expensesplit.app.ui.screens.bills

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.expensesplit.app.ui.components.CurrencyPicker
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.theme.LocalFinanceColors

/** Group list: who you split with, and where you currently stand with each of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onGroupClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createdGroupId by viewModel.createdGroupId.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(createdGroupId) {
        createdGroupId?.let {
            viewModel.onGroupOpened()
            onGroupClick(it)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_bills)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_group))
            }
        },
    ) { padding ->
        if (state.groups.isEmpty() && !state.isLoading) {
            EmptyState(
                icon = Icons.Filled.Groups,
                title = stringResource(R.string.empty_groups_title),
                message = stringResource(R.string.empty_groups_message),
                modifier = Modifier.padding(padding),
                action = {
                    Button(onClick = { showCreateDialog = true }) {
                        Text(stringResource(R.string.action_new_group))
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.groups, key = { it.group.id }) { summary ->
                    GroupCard(summary = summary, onClick = { onGroupClick(summary.group.id) })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            currencies = state.currencies.ifEmpty { listOf(state.baseCurrency) },
            defaultCurrency = state.baseCurrency,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, currency, members ->
                showCreateDialog = false
                viewModel.createGroup(name, currency, members)
            },
        )
    }
}

@Composable
private fun GroupCard(summary: GroupSummary, onClick: () -> Unit) {
    val finance = LocalFinanceColors.current
    val net = summary.yourNetMinor

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.group_members_bills,
                        summary.memberCount,
                        summary.billCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                when {
                    net > 0 -> {
                        Text(
                            text = stringResource(R.string.bills_you_are_owed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMoney(net, summary.group.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = finance.positive,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    net < 0 -> {
                        Text(
                            text = stringResource(R.string.bills_you_owe),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMoney(-net, summary.group.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = finance.negative,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    else -> Text(
                        text = stringResource(R.string.bills_settled_up),
                        style = MaterialTheme.typography.bodyMedium,
                        color = finance.positive,
                    )
                }
            }
        }
    }
}

/** Create-group dialog: name, currency and the people you split with. */
@Composable
private fun CreateGroupDialog(
    currencies: List<String>,
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(defaultCurrency) }
    val members = remember { mutableStateListOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_group_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.field_currency),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CurrencyPicker(
                        currencies = currencies,
                        selected = currency,
                        onSelected = { currency = it },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.dialog_new_group_members),
                    style = MaterialTheme.typography.labelLarge,
                )

                members.forEachIndexed { index, value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { members[index] = it },
                            label = { Text(stringResource(R.string.field_member_name)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        if (members.size > 1) {
                            IconButton(onClick = { members.removeAt(index) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.action_remove),
                                )
                            }
                        }
                    }
                }

                TextButton(onClick = { members.add("") }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    Text(stringResource(R.string.action_add_member))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, currency, members.toList()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
