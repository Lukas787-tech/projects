package com.expensesplit.app.domain.model

import com.expensesplit.app.R

enum class PaymentMethod(val labelRes: Int) {
    CASH(R.string.payment_cash),
    CARD(R.string.payment_card),
    DEBIT(R.string.payment_debit),
    BANK_TRANSFER(R.string.payment_bank_transfer),
    MOBILE_WALLET(R.string.payment_mobile_wallet),
    OTHER(R.string.payment_other);

    companion object {
        fun fromNameOrDefault(value: String?): PaymentMethod =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class SplitMethod(val labelRes: Int) {
    EQUAL(R.string.split_equal),
    PERCENTAGE(R.string.split_percentage),
    CUSTOM(R.string.split_custom),
    SHARES(R.string.split_shares);
}

enum class RecurrenceFrequency(val labelRes: Int) {
    DAILY(R.string.recurrence_daily),
    WEEKLY(R.string.recurrence_weekly),
    MONTHLY(R.string.recurrence_monthly),
    YEARLY(R.string.recurrence_yearly);
}

enum class BudgetPeriod(val labelRes: Int) {
    WEEKLY(R.string.budget_period_weekly),
    MONTHLY(R.string.budget_period_monthly),
    YEARLY(R.string.budget_period_yearly);
}

enum class SettlementStatus { UNSETTLED, PARTIALLY_SETTLED, SETTLED }

enum class ThemeMode(val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);
}

/** Where a price observation came from, which drives how much the app trusts it. */
enum class PriceSource { OWN_RECEIPT, MANUAL_ENTRY, PARTNER_FEED }
