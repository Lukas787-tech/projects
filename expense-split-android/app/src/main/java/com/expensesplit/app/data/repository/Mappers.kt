package com.expensesplit.app.data.repository

import com.expensesplit.app.data.local.entity.BillEntity
import com.expensesplit.app.data.local.entity.BillShareEntity
import com.expensesplit.app.data.local.entity.BudgetEntity
import com.expensesplit.app.data.local.entity.CategoryEntity
import com.expensesplit.app.data.local.entity.ExpenseEntity
import com.expensesplit.app.data.local.entity.GroupEntity
import com.expensesplit.app.data.local.entity.MemberEntity
import com.expensesplit.app.data.local.entity.PricePointEntity
import com.expensesplit.app.data.local.entity.ReceiptEntity
import com.expensesplit.app.data.local.entity.ReceiptItemEntity
import com.expensesplit.app.data.local.entity.RecurringRuleEntity
import com.expensesplit.app.data.local.entity.SettlementEntity
import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.BillShare
import com.expensesplit.app.domain.model.Budget
import com.expensesplit.app.domain.model.BudgetPeriod
import com.expensesplit.app.domain.model.Category
import com.expensesplit.app.domain.model.Expense
import com.expensesplit.app.domain.model.ExpenseGroup
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.PaymentMethod
import com.expensesplit.app.domain.model.PricePoint
import com.expensesplit.app.domain.model.PriceSource
import com.expensesplit.app.domain.model.Receipt
import com.expensesplit.app.domain.model.ReceiptItem
import com.expensesplit.app.domain.model.RecurrenceFrequency
import com.expensesplit.app.domain.model.RecurringRule
import com.expensesplit.app.domain.model.Settlement
import com.expensesplit.app.domain.model.SplitMethod

/**
 * Entity <-> domain conversions. Kept in one file so the two shapes are always edited together.
 *
 * Enums are stored by name; an unrecognised value (an older or newer schema) degrades to a sane
 * default rather than throwing, because a single bad row should never make the list screen crash.
 */

fun CategoryEntity.toDomain(nameRes: Int?): Category = Category(
    id = id,
    key = key,
    nameRes = nameRes,
    customName = customName,
    colorArgb = colorArgb,
    iconKey = iconKey,
    keywords = keywords,
    isBuiltIn = isBuiltIn,
)

fun ExpenseEntity.toDomain(): Expense = Expense(
    id = id,
    title = title,
    note = note,
    categoryId = categoryId,
    amountMinor = amountMinor,
    currency = currency,
    baseAmountMinor = baseAmountMinor,
    fxRate = fxRate,
    date = date,
    paymentMethod = PaymentMethod.fromNameOrDefault(paymentMethod),
    merchant = merchant,
    receiptId = receiptId,
    groupId = groupId,
    recurringRuleId = recurringRuleId,
    attachmentUri = attachmentUri,
    isSettled = isSettled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Expense.toEntity(): ExpenseEntity = ExpenseEntity(
    id = id,
    title = title,
    note = note,
    categoryId = categoryId,
    amountMinor = amountMinor,
    currency = currency,
    baseAmountMinor = baseAmountMinor,
    fxRate = fxRate,
    date = date,
    paymentMethod = paymentMethod.name,
    merchant = merchant,
    receiptId = receiptId,
    groupId = groupId,
    recurringRuleId = recurringRuleId,
    attachmentUri = attachmentUri,
    isSettled = isSettled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ReceiptEntity.toDomain(): Receipt = Receipt(
    id = id,
    expenseId = expenseId,
    imageUri = imageUri,
    merchant = merchant,
    purchasedAt = purchasedAt,
    totalMinor = totalMinor,
    taxMinor = taxMinor,
    currency = currency,
    rawText = rawText,
    scanConfidence = scanConfidence,
    createdAt = createdAt,
)

fun Receipt.toEntity(): ReceiptEntity = ReceiptEntity(
    id = id,
    expenseId = expenseId,
    imageUri = imageUri,
    merchant = merchant,
    purchasedAt = purchasedAt,
    totalMinor = totalMinor,
    taxMinor = taxMinor,
    currency = currency,
    rawText = rawText,
    scanConfidence = scanConfidence,
    createdAt = createdAt,
)

fun ReceiptItemEntity.toDomain(): ReceiptItem = ReceiptItem(
    id = id,
    receiptId = receiptId,
    name = name,
    normalizedName = normalizedName,
    quantity = quantity,
    unitPriceMinor = unitPriceMinor,
    totalPriceMinor = totalPriceMinor,
    currency = currency,
    categoryId = categoryId,
)

fun ReceiptItem.toEntity(): ReceiptItemEntity = ReceiptItemEntity(
    id = id,
    receiptId = receiptId,
    name = name,
    normalizedName = normalizedName,
    quantity = quantity,
    unitPriceMinor = unitPriceMinor,
    totalPriceMinor = totalPriceMinor,
    currency = currency,
    categoryId = categoryId,
)

fun GroupEntity.toDomain(): ExpenseGroup = ExpenseGroup(id, name, currency, createdAt, archived)

fun ExpenseGroup.toEntity(): GroupEntity = GroupEntity(id, name, currency, createdAt, archived)

fun MemberEntity.toDomain(): Member = Member(id, groupId, name, email, avatarColorArgb, isSelf)

fun Member.toEntity(): MemberEntity = MemberEntity(id, groupId, name, email, avatarColorArgb, isSelf)

fun BillEntity.toDomain(): Bill = Bill(
    id = id,
    groupId = groupId,
    title = title,
    totalMinor = totalMinor,
    currency = currency,
    paidByMemberId = paidByMemberId,
    date = date,
    splitMethod = SplitMethod.entries.firstOrNull { it.name == splitMethod } ?: SplitMethod.EQUAL,
    note = note,
    settled = settled,
    createdAt = createdAt,
)

fun Bill.toEntity(): BillEntity = BillEntity(
    id = id,
    groupId = groupId,
    title = title,
    totalMinor = totalMinor,
    currency = currency,
    paidByMemberId = paidByMemberId,
    date = date,
    splitMethod = splitMethod.name,
    note = note,
    settled = settled,
    createdAt = createdAt,
)

fun BillShareEntity.toDomain(): BillShare = BillShare(id, billId, memberId, shareMinor, weight)

fun BillShare.toEntity(): BillShareEntity = BillShareEntity(id, billId, memberId, shareMinor, weight)

fun SettlementEntity.toDomain(): Settlement =
    Settlement(id, groupId, fromMemberId, toMemberId, amountMinor, currency, settledAt, note)

fun Settlement.toEntity(): SettlementEntity =
    SettlementEntity(id, groupId, fromMemberId, toMemberId, amountMinor, currency, settledAt, note)

fun BudgetEntity.toDomain(): Budget = Budget(
    id = id,
    categoryId = categoryId,
    period = BudgetPeriod.entries.firstOrNull { it.name == period } ?: BudgetPeriod.MONTHLY,
    limitMinor = limitMinor,
    currency = currency,
    startDate = startDate,
    alertThresholdPercent = alertThresholdPercent,
    active = active,
)

fun Budget.toEntity(): BudgetEntity = BudgetEntity(
    id = id,
    categoryId = categoryId,
    period = period.name,
    limitMinor = limitMinor,
    currency = currency,
    startDate = startDate,
    alertThresholdPercent = alertThresholdPercent,
    active = active,
)

fun RecurringRuleEntity.toDomain(): RecurringRule = RecurringRule(
    id = id,
    title = title,
    categoryId = categoryId,
    amountMinor = amountMinor,
    currency = currency,
    paymentMethod = PaymentMethod.fromNameOrDefault(paymentMethod),
    merchant = merchant,
    frequency = RecurrenceFrequency.entries.firstOrNull { it.name == frequency }
        ?: RecurrenceFrequency.MONTHLY,
    interval = interval,
    nextRunDate = nextRunDate,
    endDate = endDate,
    lastRunDate = lastRunDate,
    active = active,
)

fun RecurringRule.toEntity(): RecurringRuleEntity = RecurringRuleEntity(
    id = id,
    title = title,
    categoryId = categoryId,
    amountMinor = amountMinor,
    currency = currency,
    paymentMethod = paymentMethod.name,
    merchant = merchant,
    frequency = frequency.name,
    interval = interval,
    nextRunDate = nextRunDate,
    endDate = endDate,
    lastRunDate = lastRunDate,
    active = active,
)

fun PricePointEntity.toDomain(): PricePoint = PricePoint(
    id = id,
    normalizedItemName = normalizedItemName,
    displayName = displayName,
    storeName = storeName,
    unitPriceMinor = unitPriceMinor,
    currency = currency,
    observedOn = observedOn,
    source = PriceSource.entries.firstOrNull { it.name == source } ?: PriceSource.OWN_RECEIPT,
    receiptItemId = receiptItemId,
)

fun PricePoint.toEntity(): PricePointEntity = PricePointEntity(
    id = id,
    normalizedItemName = normalizedItemName,
    displayName = displayName,
    storeName = storeName,
    unitPriceMinor = unitPriceMinor,
    currency = currency,
    observedOn = observedOn,
    source = source.name,
    receiptItemId = receiptItemId,
)
