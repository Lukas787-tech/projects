package com.expensesplit.app.data.preferences

import com.expensesplit.app.domain.model.ThemeMode

data class UserPreferences(
    val baseCurrency: String = "USD",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** BCP-47 tag, or empty to follow the device language. */
    val languageTag: String = "",
    val notificationsEnabled: Boolean = true,
    val budgetAlertsEnabled: Boolean = true,
    val billRemindersEnabled: Boolean = true,
    val saleAlertsEnabled: Boolean = true,
    val recurringAutoPost: Boolean = true,
    val encryptExports: Boolean = false,
    val cloudBackupEnabled: Boolean = false,
    val lastBackupAt: Long = 0L,
    val weekStartsMonday: Boolean = true,
    val onboardingComplete: Boolean = false,
    val selfDisplayName: String = "",
)
