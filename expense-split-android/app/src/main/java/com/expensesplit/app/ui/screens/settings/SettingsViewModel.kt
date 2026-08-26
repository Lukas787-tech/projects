package com.expensesplit.app.ui.screens.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.AppLocales
import com.expensesplit.app.data.export.BackupManager
import com.expensesplit.app.data.export.CryptoManager
import com.expensesplit.app.data.export.CsvExporter
import com.expensesplit.app.data.export.FileSharer
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.preferences.UserPreferences
import com.expensesplit.app.data.repository.CurrencyRepository
import com.expensesplit.app.data.repository.ExpenseRepository
import com.expensesplit.app.domain.model.ThemeMode
import com.expensesplit.app.notifications.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Transient outcomes the screen surfaces as a snackbar or dialog. */
sealed interface SettingsEvent {
    data class BackupCreated(val file: File) : SettingsEvent
    data class BackupRestored(val summary: BackupManager.BackupSummary) : SettingsEvent
    data class ExportReady(val file: File) : SettingsEvent
    data class Failed(val messageKey: FailureReason, val detail: String? = null) : SettingsEvent
}

enum class FailureReason { WRONG_PASSPHRASE, UNREADABLE_FILE, INCOMPATIBLE_VERSION, UNKNOWN }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val currencyRepository: CurrencyRepository,
    private val backupManager: BackupManager,
    private val csvExporter: CsvExporter,
    private val expenseRepository: ExpenseRepository,
    private val fileSharer: FileSharer,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    private val _event = MutableStateFlow<SettingsEvent?>(null)
    val event: StateFlow<SettingsEvent?> = _event.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val currencies: List<String> get() = currencyRepository.supportedCurrencies
    val languages: List<AppLocales.Option> get() = AppLocales.supported

    fun currencyLabel(code: String): String = currencyRepository.displayName(code)

    fun setBaseCurrency(code: String) = launchPreference { setBaseCurrency(code) }

    fun setThemeMode(mode: ThemeMode) = launchPreference { setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = launchPreference { setDynamicColor(enabled) }

    /**
     * Applies the language immediately as well as storing it: the per-app locale API is what makes
     * the change visible, and the stored value is what survives a cold start.
     */
    fun setLanguage(tag: String) {
        viewModelScope.launch {
            preferencesRepository.setLanguageTag(tag)
            AppLocales.apply(tag)
        }
    }

    fun setSelfName(name: String) = launchPreference { setSelfDisplayName(name) }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setNotificationsEnabled(enabled)
            if (enabled) workScheduler.scheduleAll() else workScheduler.cancelAll()
        }
    }

    fun setBudgetAlerts(enabled: Boolean) = launchPreference { setBudgetAlerts(enabled) }

    fun setBillReminders(enabled: Boolean) = launchPreference { setBillReminders(enabled) }

    fun setSaleAlerts(enabled: Boolean) = launchPreference { setSaleAlerts(enabled) }

    fun setRecurringAutoPost(enabled: Boolean) = launchPreference { setRecurringAutoPost(enabled) }

    fun setEncryptExports(enabled: Boolean) = launchPreference { setEncryptExports(enabled) }

    fun setCloudBackupEnabled(enabled: Boolean) = launchPreference { setCloudBackupEnabled(enabled) }

    /** Creates a JSON backup, encrypted when a passphrase is supplied. */
    fun createBackup(passphrase: CharArray?) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                val baseCurrency = preferencesRepository.preferences.first().baseCurrency
                backupManager.exportToFile(baseCurrency, passphrase)
            }.onSuccess { file ->
                preferencesRepository.setLastBackupAt(System.currentTimeMillis())
                _event.value = SettingsEvent.BackupCreated(file)
            }.onFailure {
                _event.value = SettingsEvent.Failed(FailureReason.UNKNOWN, it.message)
            }
            // The passphrase array is the caller's to clear; it is never retained here.
            _isBusy.value = false
        }
    }

    fun restoreBackup(uri: Uri, passphrase: CharArray?) {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching { backupManager.importFromUri(uri, passphrase) }
                .onSuccess { _event.value = SettingsEvent.BackupRestored(it) }
                .onFailure { error ->
                    _event.value = when (error) {
                        is CryptoManager.DecryptionFailedException ->
                            SettingsEvent.Failed(FailureReason.WRONG_PASSPHRASE, error.message)
                        is BackupManager.IncompatibleBackupException ->
                            SettingsEvent.Failed(FailureReason.INCOMPATIBLE_VERSION, error.message)
                        else -> SettingsEvent.Failed(FailureReason.UNREADABLE_FILE, error.message)
                    }
                }
            _isBusy.value = false
        }
    }

    fun exportAllCsv() {
        viewModelScope.launch {
            _isBusy.value = true
            runCatching {
                val baseCurrency = preferencesRepository.preferences.first().baseCurrency
                csvExporter.exportExpenses(expenseRepository.getAll(), baseCurrency)
            }.onSuccess { _event.value = SettingsEvent.ExportReady(it) }
                .onFailure { _event.value = SettingsEvent.Failed(FailureReason.UNKNOWN, it.message) }
            _isBusy.value = false
        }
    }

    fun shareIntentFor(file: File) = fileSharer.shareIntent(file)

    fun onEventHandled() {
        _event.value = null
    }

    private fun launchPreference(block: suspend PreferencesRepository.() -> Unit) {
        viewModelScope.launch { preferencesRepository.block() }
    }
}
