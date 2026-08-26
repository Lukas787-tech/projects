package com.expensesplit.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "categories",
    indices = [Index(value = ["key"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    /** Resource name (not id) so it survives a rebuild; resolved via resources at render time. */
    val nameResName: String?,
    val customName: String?,
    val colorArgb: Long,
    val iconKey: String,
    val keywords: List<String> = emptyList(),
    val isBuiltIn: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_DEFAULT,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("date"),
        Index("groupId"),
        Index("receiptId"),
        Index("recurringRuleId"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String? = null,
    @ColumnInfo(defaultValue = "1") val categoryId: Long,
    val amountMinor: Long,
    val currency: String,
    val baseAmountMinor: Long,
    val fxRate: Double = 1.0,
    val date: LocalDate,
    val paymentMethod: String,
    val merchant: String? = null,
    val receiptId: Long? = null,
    val groupId: Long? = null,
    val recurringRuleId: Long? = null,
    val attachmentUri: String? = null,
    val isSettled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "receipts",
    indices = [Index("expenseId"), Index("purchasedAt")],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long? = null,
    val imageUri: String? = null,
    val merchant: String? = null,
    val purchasedAt: LocalDate,
    val totalMinor: Long,
    val taxMinor: Long = 0,
    val currency: String,
    val rawText: String? = null,
    val scanConfidence: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "receipt_items",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("receiptId"), Index("normalizedName")],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val name: String,
    val normalizedName: String,
    val quantity: Double = 1.0,
    val unitPriceMinor: Long,
    val totalPriceMinor: Long,
    val currency: String,
    val categoryId: Long? = null,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currency: String,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
)

@Entity(
    tableName = "members",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val email: String? = null,
    val avatarColorArgb: Long,
    val isSelf: Boolean = false,
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("date")],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val title: String,
    val totalMinor: Long,
    val currency: String,
    val paidByMemberId: Long,
    val date: LocalDate,
    val splitMethod: String,
    val note: String? = null,
    val settled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "bill_shares",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId"), Index("memberId")],
)
data class BillShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val memberId: Long,
    val shareMinor: Long,
    val weight: Double? = null,
)

@Entity(
    tableName = "settlements",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class SettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amountMinor: Long,
    val currency: String,
    val settledAt: Long = System.currentTimeMillis(),
    val note: String? = null,
)

@Entity(tableName = "budgets", indices = [Index("categoryId")])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long? = null,
    val period: String,
    val limitMinor: Long,
    val currency: String,
    val startDate: LocalDate,
    val alertThresholdPercent: Int = 80,
    val active: Boolean = true,
)

@Entity(tableName = "recurring_rules", indices = [Index("nextRunDate")])
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    val merchant: String? = null,
    val frequency: String,
    val interval: Int = 1,
    val nextRunDate: LocalDate,
    val endDate: LocalDate? = null,
    val lastRunDate: LocalDate? = null,
    val active: Boolean = true,
)

@Entity(
    tableName = "price_points",
    indices = [Index("normalizedItemName"), Index("observedOn")],
)
data class PricePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedItemName: String,
    val displayName: String,
    val storeName: String,
    val unitPriceMinor: Long,
    val currency: String,
    val observedOn: LocalDate,
    val source: String,
    val receiptItemId: Long? = null,
)

/** Cached FX rates so conversion keeps working offline. */
@Entity(tableName = "fx_rates", primaryKeys = ["base", "quote"])
data class FxRateEntity(
    val base: String,
    val quote: String,
    val rate: Double,
    val fetchedAt: Long,
)
