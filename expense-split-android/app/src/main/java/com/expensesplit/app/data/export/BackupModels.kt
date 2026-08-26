package com.expensesplit.app.data.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk backup format. Deliberately its own set of DTOs rather than the Room entities, so the
 * database schema can evolve without silently breaking every backup a user already holds.
 */
@Serializable
data class BackupPayload(
    @SerialName("format_version") val formatVersion: Int = CURRENT_FORMAT_VERSION,
    @SerialName("exported_at") val exportedAt: Long = System.currentTimeMillis(),
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("base_currency") val baseCurrency: String = "USD",
    val categories: List<BackupCategory> = emptyList(),
    val expenses: List<BackupExpense> = emptyList(),
    val receipts: List<BackupReceipt> = emptyList(),
    @SerialName("receipt_items") val receiptItems: List<BackupReceiptItem> = emptyList(),
    val groups: List<BackupGroup> = emptyList(),
    val members: List<BackupMember> = emptyList(),
    val bills: List<BackupBill> = emptyList(),
    @SerialName("bill_shares") val billShares: List<BackupBillShare> = emptyList(),
    val settlements: List<BackupSettlement> = emptyList(),
    val budgets: List<BackupBudget> = emptyList(),
    @SerialName("recurring_rules") val recurringRules: List<BackupRecurringRule> = emptyList(),
    @SerialName("price_points") val pricePoints: List<BackupPricePoint> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

@Serializable
data class BackupCategory(
    val id: Long,
    val key: String,
    @SerialName("name_res") val nameResName: String? = null,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("color_argb") val colorArgb: Long,
    @SerialName("icon_key") val iconKey: String,
    val keywords: List<String> = emptyList(),
    @SerialName("is_built_in") val isBuiltIn: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class BackupExpense(
    val id: Long,
    val title: String,
    val note: String? = null,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("base_amount_minor") val baseAmountMinor: Long,
    @SerialName("fx_rate") val fxRate: Double = 1.0,
    /** ISO-8601 date, e.g. 2026-04-18. */
    val date: String,
    @SerialName("payment_method") val paymentMethod: String,
    val merchant: String? = null,
    @SerialName("receipt_id") val receiptId: Long? = null,
    @SerialName("group_id") val groupId: Long? = null,
    @SerialName("recurring_rule_id") val recurringRuleId: Long? = null,
    @SerialName("attachment_uri") val attachmentUri: String? = null,
    @SerialName("is_settled") val isSettled: Boolean = true,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class BackupReceipt(
    val id: Long,
    @SerialName("expense_id") val expenseId: Long? = null,
    @SerialName("image_uri") val imageUri: String? = null,
    val merchant: String? = null,
    @SerialName("purchased_at") val purchasedAt: String,
    @SerialName("total_minor") val totalMinor: Long,
    @SerialName("tax_minor") val taxMinor: Long = 0,
    val currency: String,
    @SerialName("raw_text") val rawText: String? = null,
    @SerialName("scan_confidence") val scanConfidence: Float = 0f,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class BackupReceiptItem(
    val id: Long,
    @SerialName("receipt_id") val receiptId: Long,
    val name: String,
    @SerialName("normalized_name") val normalizedName: String,
    val quantity: Double = 1.0,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("total_price_minor") val totalPriceMinor: Long,
    val currency: String,
    @SerialName("category_id") val categoryId: Long? = null,
)

@Serializable
data class BackupGroup(
    val id: Long,
    val name: String,
    val currency: String,
    @SerialName("created_at") val createdAt: Long = 0,
    val archived: Boolean = false,
)

@Serializable
data class BackupMember(
    val id: Long,
    @SerialName("group_id") val groupId: Long,
    val name: String,
    val email: String? = null,
    @SerialName("avatar_color_argb") val avatarColorArgb: Long,
    @SerialName("is_self") val isSelf: Boolean = false,
)

@Serializable
data class BackupBill(
    val id: Long,
    @SerialName("group_id") val groupId: Long,
    val title: String,
    @SerialName("total_minor") val totalMinor: Long,
    val currency: String,
    @SerialName("paid_by_member_id") val paidByMemberId: Long,
    val date: String,
    @SerialName("split_method") val splitMethod: String,
    val note: String? = null,
    val settled: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class BackupBillShare(
    val id: Long,
    @SerialName("bill_id") val billId: Long,
    @SerialName("member_id") val memberId: Long,
    @SerialName("share_minor") val shareMinor: Long,
    val weight: Double? = null,
)

@Serializable
data class BackupSettlement(
    val id: Long,
    @SerialName("group_id") val groupId: Long,
    @SerialName("from_member_id") val fromMemberId: Long,
    @SerialName("to_member_id") val toMemberId: Long,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("settled_at") val settledAt: Long = 0,
    val note: String? = null,
)

@Serializable
data class BackupBudget(
    val id: Long,
    @SerialName("category_id") val categoryId: Long? = null,
    val period: String,
    @SerialName("limit_minor") val limitMinor: Long,
    val currency: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("alert_threshold_percent") val alertThresholdPercent: Int = 80,
    val active: Boolean = true,
)

@Serializable
data class BackupRecurringRule(
    val id: Long,
    val title: String,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String,
    @SerialName("payment_method") val paymentMethod: String,
    val merchant: String? = null,
    val frequency: String,
    val interval: Int = 1,
    @SerialName("next_run_date") val nextRunDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("last_run_date") val lastRunDate: String? = null,
    val active: Boolean = true,
)

@Serializable
data class BackupPricePoint(
    val id: Long,
    @SerialName("normalized_item_name") val normalizedItemName: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("store_name") val storeName: String,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    val currency: String,
    @SerialName("observed_on") val observedOn: String,
    val source: String,
    @SerialName("receipt_item_id") val receiptItemId: Long? = null,
)
