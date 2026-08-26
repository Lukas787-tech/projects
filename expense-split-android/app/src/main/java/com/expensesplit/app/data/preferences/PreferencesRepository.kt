package com.expensesplit.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.expensesplit.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

@Singleton
class PreferencesRepository @Inject constructor(
    private val context: Context,
) {

    private object Keys {
        val BASE_CURRENCY = stringPreferencesKey("base_currency")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val BUDGET_ALERTS = booleanPreferencesKey("budget_alerts")
        val BILL_REMINDERS = booleanPreferencesKey("bill_reminders")
        val SALE_ALERTS = booleanPreferencesKey("sale_alerts")
        val RECURRING_AUTO_POST = booleanPreferencesKey("recurring_auto_post")
        val ENCRYPT_EXPORTS = booleanPreferencesKey("encrypt_exports")
        val CLOUD_BACKUP = booleanPreferencesKey("cloud_backup")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val WEEK_STARTS_MONDAY = booleanPreferencesKey("week_starts_monday")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val SELF_NAME = stringPreferencesKey("self_display_name")
    }

    /** A corrupt or unreadable store falls back to defaults rather than taking the app down. */
    val preferences: Flow<UserPreferences> = context.dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { stored ->
            UserPreferences(
                baseCurrency = stored[Keys.BASE_CURRENCY] ?: defaultCurrency(),
                themeMode = stored[Keys.THEME_MODE]
                    ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                    ?: ThemeMode.SYSTEM,
                dynamicColor = stored[Keys.DYNAMIC_COLOR] ?: true,
                languageTag = stored[Keys.LANGUAGE_TAG].orEmpty(),
                notificationsEnabled = stored[Keys.NOTIFICATIONS] ?: true,
                budgetAlertsEnabled = stored[Keys.BUDGET_ALERTS] ?: true,
                billRemindersEnabled = stored[Keys.BILL_REMINDERS] ?: true,
                saleAlertsEnabled = stored[Keys.SALE_ALERTS] ?: true,
                recurringAutoPost = stored[Keys.RECURRING_AUTO_POST] ?: true,
                encryptExports = stored[Keys.ENCRYPT_EXPORTS] ?: false,
                cloudBackupEnabled = stored[Keys.CLOUD_BACKUP] ?: false,
                lastBackupAt = stored[Keys.LAST_BACKUP_AT] ?: 0L,
                weekStartsMonday = stored[Keys.WEEK_STARTS_MONDAY] ?: true,
                onboardingComplete = stored[Keys.ONBOARDING_COMPLETE] ?: false,
                selfDisplayName = stored[Keys.SELF_NAME].orEmpty(),
            )
        }

    suspend fun setBaseCurrency(code: String) = edit { it[Keys.BASE_CURRENCY] = code }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setLanguageTag(tag: String) = edit { it[Keys.LANGUAGE_TAG] = tag }
    suspend fun setNotificationsEnabled(enabled: Boolean) = edit { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setBudgetAlerts(enabled: Boolean) = edit { it[Keys.BUDGET_ALERTS] = enabled }
    suspend fun setBillReminders(enabled: Boolean) = edit { it[Keys.BILL_REMINDERS] = enabled }
    suspend fun setSaleAlerts(enabled: Boolean) = edit { it[Keys.SALE_ALERTS] = enabled }
    suspend fun setRecurringAutoPost(enabled: Boolean) = edit { it[Keys.RECURRING_AUTO_POST] = enabled }
    suspend fun setEncryptExports(enabled: Boolean) = edit { it[Keys.ENCRYPT_EXPORTS] = enabled }
    suspend fun setCloudBackupEnabled(enabled: Boolean) = edit { it[Keys.CLOUD_BACKUP] = enabled }
    suspend fun setLastBackupAt(timestamp: Long) = edit { it[Keys.LAST_BACKUP_AT] = timestamp }
    suspend fun setWeekStartsMonday(enabled: Boolean) = edit { it[Keys.WEEK_STARTS_MONDAY] = enabled }
    suspend fun setOnboardingComplete(complete: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    suspend fun setSelfDisplayName(name: String) = edit { it[Keys.SELF_NAME] = name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    /** Seeds the base currency from the device locale on first launch. */
    private fun defaultCurrency(): String = runCatching {
        java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode
    }.getOrDefault("USD")
}
