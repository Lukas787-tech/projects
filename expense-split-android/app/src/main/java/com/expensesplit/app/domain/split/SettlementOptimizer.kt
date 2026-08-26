package com.expensesplit.app.domain.split

import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.BillShare
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.MemberBalance
import com.expensesplit.app.domain.model.Settlement
import com.expensesplit.app.domain.model.SettlementSuggestion

/**
 * Reduces a web of "A owes B, B owes C" into the fewest possible transfers.
 *
 * The algorithm is the standard greedy debt simplification: compute each member's net position,
 * then repeatedly match the largest creditor with the largest debtor. For n members it produces at
 * most n-1 transfers, which is the practical minimum for real group sizes.
 */
object SettlementOptimizer {

    /**
     * Net balances per member. Positive = the group owes them; negative = they owe the group.
     * Recorded [settlements] are applied on top so a paid-off debt stops showing up.
     */
    fun balances(
        members: List<Member>,
        bills: List<Bill>,
        sharesByBillId: Map<Long, List<BillShare>>,
        settlements: List<Settlement> = emptyList(),
    ): List<MemberBalance> {
        val paid = mutableMapOf<Long, Long>()
        val owed = mutableMapOf<Long, Long>()

        for (bill in bills) {
            paid[bill.paidByMemberId] = (paid[bill.paidByMemberId] ?: 0L) + bill.totalMinor
            for (share in sharesByBillId[bill.id].orEmpty()) {
                owed[share.memberId] = (owed[share.memberId] ?: 0L) + share.shareMinor
            }
        }

        // A recorded settlement is cash that moved: the payer has effectively paid that much more.
        for (settlement in settlements) {
            paid[settlement.fromMemberId] = (paid[settlement.fromMemberId] ?: 0L) + settlement.amountMinor
            paid[settlement.toMemberId] = (paid[settlement.toMemberId] ?: 0L) - settlement.amountMinor
        }

        return members.map { member ->
            val paidMinor = paid[member.id] ?: 0L
            val owedMinor = owed[member.id] ?: 0L
            MemberBalance(
                member = member,
                netMinor = paidMinor - owedMinor,
                paidMinor = paidMinor,
                owedMinor = owedMinor,
            )
        }
    }

    /** Greedy creditor/debtor matching over the net balances. */
    fun suggestSettlements(balances: List<MemberBalance>, currency: String): List<SettlementSuggestion> {
        val creditors = balances.filter { it.netMinor > 0 }
            .map { it.member.id to it.netMinor }
            .toMutableList()
        val debtors = balances.filter { it.netMinor < 0 }
            .map { it.member.id to -it.netMinor }
            .toMutableList()

        creditors.sortByDescending { it.second }
        debtors.sortByDescending { it.second }

        val suggestions = mutableListOf<SettlementSuggestion>()
        var creditorIndex = 0
        var debtorIndex = 0

        while (creditorIndex < creditors.size && debtorIndex < debtors.size) {
            val (creditorId, credit) = creditors[creditorIndex]
            val (debtorId, debt) = debtors[debtorIndex]
            val transfer = minOf(credit, debt)

            if (transfer > 0) {
                suggestions += SettlementSuggestion(
                    fromMemberId = debtorId,
                    toMemberId = creditorId,
                    amountMinor = transfer,
                    currency = currency,
                )
            }

            val creditLeft = credit - transfer
            val debtLeft = debt - transfer
            creditors[creditorIndex] = creditorId to creditLeft
            debtors[debtorIndex] = debtorId to debtLeft

            if (creditLeft == 0L) creditorIndex++
            if (debtLeft == 0L) debtorIndex++
        }

        return suggestions
    }

    /** True when every member's net position has been squared off. */
    fun isFullySettled(balances: List<MemberBalance>): Boolean = balances.all { it.netMinor == 0L }
}
