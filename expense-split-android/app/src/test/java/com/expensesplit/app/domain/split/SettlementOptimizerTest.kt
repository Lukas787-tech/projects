package com.expensesplit.app.domain.split

import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.BillShare
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.Settlement
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class SettlementOptimizerTest {

    private val alice = Member(1, groupId = 1, name = "Alice", avatarColorArgb = 0, isSelf = true)
    private val bob = Member(2, groupId = 1, name = "Bob", avatarColorArgb = 0)
    private val carol = Member(3, groupId = 1, name = "Carol", avatarColorArgb = 0)
    private val members = listOf(alice, bob, carol)

    private fun bill(id: Long, paidBy: Long, totalMinor: Long) = Bill(
        id = id,
        groupId = 1,
        title = "Bill $id",
        totalMinor = totalMinor,
        currency = "USD",
        paidByMemberId = paidBy,
        date = LocalDate.of(2026, 4, 1),
    )

    private fun equalShares(billId: Long, totalMinor: Long): List<BillShare> {
        val each = totalMinor / members.size
        return members.mapIndexed { index, member ->
            BillShare(
                id = billId * 10 + index,
                billId = billId,
                memberId = member.id,
                shareMinor = if (index == 0) totalMinor - each * (members.size - 1) else each,
            )
        }
    }

    @Test
    fun `one payer leaves the others owing an equal share`() {
        val balances = SettlementOptimizer.balances(
            members = members,
            bills = listOf(bill(1, paidBy = alice.id, totalMinor = 3000)),
            sharesByBillId = mapOf(1L to equalShares(1, 3000)),
        )

        val aliceBalance = balances.first { it.member.id == alice.id }
        assertThat(aliceBalance.netMinor).isEqualTo(2000)
        assertThat(balances.first { it.member.id == bob.id }.netMinor).isEqualTo(-1000)
        assertThat(balances.sumOf { it.netMinor }).isEqualTo(0)
    }

    @Test
    fun `settlement plan clears every balance`() {
        val balances = SettlementOptimizer.balances(
            members = members,
            bills = listOf(bill(1, alice.id, 3000)),
            sharesByBillId = mapOf(1L to equalShares(1, 3000)),
        )
        val plan = SettlementOptimizer.suggestSettlements(balances, "USD")

        assertThat(plan).hasSize(2)
        assertThat(plan.all { it.toMemberId == alice.id }).isTrue()
        assertThat(plan.sumOf { it.amountMinor }).isEqualTo(2000)
    }

    @Test
    fun `mutual debts are netted into the fewest transfers`() {
        // Alice pays 3000, Bob pays 3000 — everything cancels except Carol's two shares.
        val bills = listOf(bill(1, alice.id, 3000), bill(2, bob.id, 3000))
        val shares = mapOf(1L to equalShares(1, 3000), 2L to equalShares(2, 3000))

        val balances = SettlementOptimizer.balances(members, bills, shares)
        val plan = SettlementOptimizer.suggestSettlements(balances, "USD")

        // Carol owes 2000 in total; nobody should be asked to pay and be paid in the same plan.
        assertThat(plan.map { it.fromMemberId }.distinct()).containsExactly(carol.id)
        assertThat(plan.sumOf { it.amountMinor }).isEqualTo(2000)
    }

    @Test
    fun `recorded settlements remove the debt they cover`() {
        val bills = listOf(bill(1, alice.id, 3000))
        val shares = mapOf(1L to equalShares(1, 3000))

        val settled = SettlementOptimizer.balances(
            members = members,
            bills = bills,
            sharesByBillId = shares,
            settlements = listOf(
                Settlement(
                    groupId = 1,
                    fromMemberId = bob.id,
                    toMemberId = alice.id,
                    amountMinor = 1000,
                    currency = "USD",
                ),
            ),
        )

        assertThat(settled.first { it.member.id == bob.id }.netMinor).isEqualTo(0)
        assertThat(settled.first { it.member.id == alice.id }.netMinor).isEqualTo(1000)
    }

    @Test
    fun `a fully settled group produces no transfers`() {
        val balances = SettlementOptimizer.balances(members, emptyList(), emptyMap())

        assertThat(SettlementOptimizer.isFullySettled(balances)).isTrue()
        assertThat(SettlementOptimizer.suggestSettlements(balances, "USD")).isEmpty()
    }

    @Test
    fun `the plan never needs more transfers than members minus one`() {
        val bills = listOf(
            bill(1, alice.id, 3000),
            bill(2, bob.id, 1500),
            bill(3, carol.id, 900),
        )
        val shares = mapOf(
            1L to equalShares(1, 3000),
            2L to equalShares(2, 1500),
            3L to equalShares(3, 900),
        )

        val balances = SettlementOptimizer.balances(members, bills, shares)
        val plan = SettlementOptimizer.suggestSettlements(balances, "USD")

        assertThat(plan.size).isAtMost(members.size - 1)
    }
}
