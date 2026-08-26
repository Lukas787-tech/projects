package com.expensesplit.app.domain.model

import java.time.LocalDate

data class ExpenseGroup(
    val id: Long = 0,
    val name: String,
    val currency: String,
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
)

data class Member(
    val id: Long = 0,
    val groupId: Long,
    val name: String,
    val email: String? = null,
    val avatarColorArgb: Long,
    /** Exactly one member per group represents the device owner. */
    val isSelf: Boolean = false,
)

data class Bill(
    val id: Long = 0,
    val groupId: Long,
    val title: String,
    val totalMinor: Long,
    val currency: String,
    val paidByMemberId: Long,
    val date: LocalDate,
    val splitMethod: SplitMethod = SplitMethod.EQUAL,
    val note: String? = null,
    val settled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BillShare(
    val id: Long = 0,
    val billId: Long,
    val memberId: Long,
    val shareMinor: Long,
    /** Percentage (0..100) for PERCENTAGE splits, share count for SHARES splits, else null. */
    val weight: Double? = null,
)

data class BillWithShares(
    val bill: Bill,
    val shares: List<BillShare>,
)

data class Settlement(
    val id: Long = 0,
    val groupId: Long,
    val fromMemberId: Long,
    val toMemberId: Long,
    val amountMinor: Long,
    val currency: String,
    val settledAt: Long = System.currentTimeMillis(),
    val note: String? = null,
)

/** A single "X pays Y" instruction produced by the settlement optimiser. */
data class SettlementSuggestion(
    val fromMemberId: Long,
    val toMemberId: Long,
    val amountMinor: Long,
    val currency: String,
)

/** Net position of one member: positive means the group owes them money. */
data class MemberBalance(
    val member: Member,
    val netMinor: Long,
    val paidMinor: Long,
    val owedMinor: Long,
)
