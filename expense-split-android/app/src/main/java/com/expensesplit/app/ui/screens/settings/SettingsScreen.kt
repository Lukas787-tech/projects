package com.expensesplit.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RestorePage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.domain.model.ThemeMode
import com.expensesplit.app.ui.components.ChipRow
import com.expensesplit.app.ui.components.DropdownField
import com.expensesplit.app.ui.components.SectionHeader
import com.expensesplit.app.ui.components.formatDate
import java.time.Instant
import java.time.ZoneId

/** Settings: language, currency, appearance, notifications, data and privacy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenRecurring: () -> Unit,
    onOpenReceiptGallery: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showBackupDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingRestoreUri = it } }

    LaunchedEffect(event) {
        when (val current = event) {
            is SettingsEvent.BackupCreated -> {
                context.startActivity(
                    viewModel.shareIntentFor(current.file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                viewModel.onEventHandled()
            }

            is SettingsEvent.ExportReady -> {
                context.startActivity(
                    viewModel.shareIntentFor(current.file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                viewModel.onEventHandled()
            }

            is SettingsEvent.BackupRestored -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.backup_restored, current.summary.expenses),
                )
                viewModel.onEventHandled()
            }

            is SettingsEvent.Failed -> {
                snackbarHostState.showSnackbar(
                    context.getString(
                        when (current.messageKey) {
                            FailureReason.WRONG_PASSPHRASE -> R.string.backup_error_passphrase
                            FailureReason.UNREADABLE_FILE -> R.string.backup_error_unreadable
                            FailureReason.INCOMPATIBLE_VERSION -> R.string.backup_error_version
                            FailureReason.UNKNOWN -> R.string.backup_error_unknown
                        },
                    ),
                )
                viewModel.onEventHandled()
            }

            null -> Unit
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_settings)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (isBusy) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            item { SectionHeader(stringResource(R.string.settings_section_general)) }

            item {
                SettingsCard {
                    DropdownField(
                        label = stringResource(R.string.settings_language),
                        options = viewModel.languages,
                        selected = viewModel.languages.firstOrNull {
                            it.tag == preferences.languageTag
                        },
                        optionLabel = { option ->
                            if (option.tag.isBlank()) {
                                stringResource(R.string.settings_language_system)
                            } else {
                                option.nativeName
                            }
                        },
                        onSelected = { viewModel.setLanguage(it.tag) },
                    )

                    Spacer(Modifier.height(12.dp))

                    DropdownField(
                        label = stringResource(R.string.settings_base_currency),
                        options = viewModel.currencies,
                        selected = preferences.baseCurrency,
                        optionLabel = { viewModel.currencyLabel(it) },
                        onSelected = viewModel::setBaseCurrency,
                    )

                    Spacer(Modifier.height(12.dp))

                    var name by remember(preferences.selfDisplayName) {
                        mutableStateOf(preferences.selfDisplayName)
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            viewModel.setSelfName(it)
                        },
                        label = { Text(stringResource(R.string.settings_your_name)) },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.settings_your_name_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }

            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        options = ThemeMode.entries,
                        selected = preferences.themeMode,
                        optionLabel = { stringResource(it.labelRes) },
                        onSelected = viewModel::setThemeMode,
                    )

                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_hint),
                        checked = preferences.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_section_notifications)) }

            item {
                SettingsCard {
                    ToggleRow(
                        title = stringResource(R.string.settings_notifications),
                        subtitle = stringResource(R.string.settings_notifications_hint),
                        checked = preferences.notificationsEnabled,
                        onCheckedChange = viewModel::setNotificationsEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ToggleRow(
                        title = stringResource(R.string.settings_budget_alerts),
                        checked = preferences.budgetAlertsEnabled,
                        enabled = preferences.notificationsEnabled,
                        onCheckedChange = viewModel::setBudgetAlerts,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_bill_reminders),
                        checked = preferences.billRemindersEnabled,
                        enabled = preferences.notificationsEnabled,
                        onCheckedChange = viewModel::setBillReminders,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_sale_alerts),
                        subtitle = stringResource(R.string.settings_sale_alerts_hint),
                        checked = preferences.saleAlertsEnabled,
                        enabled = preferences.notificationsEnabled,
                        onCheckedChange = viewModel::setSaleAlerts,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.settings_section_data)) }

            item {
                SettingsCard {
                    ToggleRow(
                        title = stringResource(R.string.settings_recurring_auto_post),
                        subtitle = stringResource(R.string.settings_recurring_auto_post_hint),
                        checked = preferences.recurringAutoPost,
                        onCheckedChange = viewModel::setRecurringAutoPost,
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    ActionRow(
                        icon = Icons.Filled.Repeat,
                        title = stringResource(R.string.settings_manage_recurring),
                        onClick = onOpenRecurring,
                    )
                    ActionRow(
                        icon = Icons.Filled.Language,
                        title = stringResource(R.string.settings_receipt_gallery),
                        onClick = onOpenReceiptGallery,
                    )
                    ActionRow(
                        icon = Icons.Filled.FileDownload,
                        title = stringResource(R.string.settings_export_csv),
                        subtitle = stringResource(R.string.settings_export_csv_hint),
                        onClick = viewModel::exportAllCsv,
                    )
                    ActionRow(
                        icon = Icons.Filled.Backup,
                        title = stringResource(R.string.settings_create_backup),
                        subtitle = stringResource(R.string.settings_create_backup_hint),
                        onClick = { showBackupDialog = true },
                    )
                    ActionRow(
                        icon = Icons.Filled.RestorePage,
                        title = stringResource(R.string.settings_restore_backup),
                        subtitle = stringResource(R.string.settings_restore_backup_hint),
                        onClick = {
                            restoreLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "*/*"),
                            )
                        },
                    )

                    if (preferences.lastBackupAt > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.settings_last_backup,
                                formatDate(
                                    Instant.ofEpochMilli(preferences.lastBackupAt)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate(),
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.settings_section_privacy)) }

            item {
                SettingsCard {
                    ToggleRow(
                        title = stringResource(R.string.settings_encrypt_exports),
                        subtitle = stringResource(R.string.settings_encrypt_exports_hint),
                        checked = preferences.encryptExports,
                        onCheckedChange = viewModel::setEncryptExports,
                    )
                    ToggleRow(
                        title = stringResource(R.string.settings_cloud_backup),
                        subtitle = stringResource(R.string.settings_cloud_backup_hint),
                        checked = preferences.cloudBackupEnabled,
                        onCheckedChange = viewModel::setCloudBackupEnabled,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Text(
                    text = stringResource(
                        R.string.settings_version,
                        com.expensesplit.app.BuildConfig.VERSION_NAME,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showBackupDialog) {
        PassphraseDialog(
            title = stringResource(R.string.settings_create_backup),
            message = stringResource(R.string.backup_passphrase_message),
            requirePassphrase = preferences.encryptExports,
            onDismiss = { showBackupDialog = false },
            onConfirm = { passphrase ->
                showBackupDialog = false
                viewModel.createBackup(passphrase)
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.settings_restore_backup),
            message = stringResource(R.string.backup_restore_warning),
            requirePassphrase = false,
            confirmLabel = stringResource(R.string.action_restore),
            onDismiss = { pendingRestoreUri = null },
            onConfirm = { passphrase ->
                pendingRestoreUri = null
                viewModel.restoreBackup(uri, passphrase)
            },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Collects an optional passphrase.
 *
 * The typed value is handed over as a [CharArray] and wiped as soon as the caller is done with it,
 * rather than lingering in an immutable String on the heap until the next GC.
 */
@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    requirePassphrase: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
    confirmLabel: String = stringResource(R.string.action_continue),
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.field_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(stringResource(R.string.field_passphrase_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(passphrase.takeIf { it.isNotBlank() }?.toCharArray())
                    passphrase = ""
                },
                enabled = !requirePassphrase || passphrase.length >= MIN_PASSPHRASE_LENGTH,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private const val MIN_PASSPHRASE_LENGTH = 8
