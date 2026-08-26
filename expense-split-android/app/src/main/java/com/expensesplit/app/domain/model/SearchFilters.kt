package com.expensesplit.app.domain.model

import com.expensesplit.app.core.DateRange

/** Everything the advanced-search screen can constrain on. All fields are optional. */
data class SearchFilters(
    val keyword: String = "",
    val range: DateRange? = null,
    val categoryIds: Set<Long> = emptySet(),
    val paymentMethods: Set<PaymentMethod> = emptySet(),
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val groupIds: Set<Long> = emptySet(),
    val settledOnly: Boolean? = null,
    val withReceiptOnly: Boolean = false,
    val sort: SearchSort = SearchSort.DATE_DESC,
) {
    val isEmpty: Boolean
        get() = keyword.isBlank() &&
            range == null &&
            categoryIds.isEmpty() &&
            paymentMethods.isEmpty() &&
            minAmountMinor == null &&
            maxAmountMinor == null &&
            groupIds.isEmpty() &&
            settledOnly == null &&
            !withReceiptOnly

    val activeFilterCount: Int
        get() = listOf(
            keyword.isNotBlank(),
            range != null,
            categoryIds.isNotEmpty(),
            paymentMethods.isNotEmpty(),
            minAmountMinor != null || maxAmountMinor != null,
            groupIds.isNotEmpty(),
            settledOnly != null,
            withReceiptOnly,
        ).count { it }
}

enum class SearchSort(val labelRes: Int) {
    DATE_DESC(com.expensesplit.app.R.string.sort_date_newest),
    DATE_ASC(com.expensesplit.app.R.string.sort_date_oldest),
    AMOUNT_DESC(com.expensesplit.app.R.string.sort_amount_highest),
    AMOUNT_ASC(com.expensesplit.app.R.string.sort_amount_lowest),
    TITLE_ASC(com.expensesplit.app.R.string.sort_title),
}
