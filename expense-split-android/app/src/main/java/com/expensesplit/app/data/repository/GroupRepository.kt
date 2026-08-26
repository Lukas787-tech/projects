package com.expensesplit.app.data.repository

import com.expensesplit.app.data.local.dao.GroupDao
import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.BillShare
import com.expensesplit.app.domain.model.BillWithShares
import com.expensesplit.app.domain.model.ExpenseGroup
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.MemberBalance
import com.expensesplit.app.domain.model.Settlement
import com.expensesplit.app.domain.model.SettlementSuggestion
import com.expensesplit.app.domain.model.SplitMethod
import com.expensesplit.app.domain.split.SettlementOptimizer
import com.expensesplit.app.domain.split.SplitCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
) {

    val groups: Flow<List<ExpenseGroup>> =
        groupDao.observeGroups().map { list -> list.map { it.toDomain() } }

    fun observeGroup(id: Long): Flow<ExpenseGroup?> = groupDao.observeGroup(id).map { it?.toDomain() }

    fun observeMembers(groupId: Long): Flow<List<Member>> =
        groupDao.observeMembers(groupId).map { list -> list.map { it.toDomain() } }

    fun observeBills(groupId: Long): Flow<List<Bill>> =
        groupDao.observeBills(groupId).map { list -> list.map { it.toDomain() } }

    fun observeSettlements(groupId: Long): Flow<List<Settlement>> =
        groupDao.observeSettlements(groupId).map { list -> list.map { it.toDomain() } }

    suspend fun getGroup(id: Long): ExpenseGroup? = groupDao.getGroup(id)?.toDomain()

    suspend fun getAllGroups(): List<ExpenseGroup> = groupDao.getAllGroups().map { it.toDomain() }

    suspend fun getMembers(groupId: Long): List<Member> = groupDao.getMembers(groupId).map { it.toDomain() }

    suspend fun getBills(groupId: Long): List<Bill> = groupDao.getBills(groupId).map { it.toDomain() }

    suspend fun getSettlements(groupId: Long): List<Settlement> =
        groupDao.getSettlements(groupId).map { it.toDomain() }

    suspend fun createGroup(name: String, currency: String, selfName: String, memberNames: List<String>): Long {
        val groupId = groupDao.insertGroup(ExpenseGroup(name = name, currency = currency).toEntity())
        val palette = MEMBER_COLORS
        val self = Member(
            groupId = groupId,
            name = selfName.ifBlank { DEFAULT_SELF_NAME },
            avatarColorArgb = palette[0],
            isSelf = true,
        )
        val others = memberNames.filter { it.isNotBlank() }.mapIndexed { index, memberName ->
            Member(
                groupId = groupId,
                name = memberName.trim(),
                avatarColorArgb = palette[(index + 1) % palette.size],
                isSelf = false,
            )
        }
        groupDao.insertMembers((listOf(self) + others).map { it.toEntity() })
        return groupId
    }

    suspend fun updateGroup(group: ExpenseGroup) = groupDao.updateGroup(group.toEntity())

    suspend fun deleteGroup(id: Long) = groupDao.deleteGroupById(id)

    suspend fun addMember(groupId: Long, name: String, email: String? = null): Long {
        val existingCount = groupDao.countMembers(groupId)
        return groupDao.insertMember(
            Member(
                groupId = groupId,
                name = name.trim(),
                email = email?.takeIf { it.isNotBlank() },
                avatarColorArgb = MEMBER_COLORS[existingCount % MEMBER_COLORS.size],
            ).toEntity(),
        )
    }

    suspend fun updateMember(member: Member) = groupDao.updateMember(member.toEntity())

    suspend fun removeMember(memberId: Long) = groupDao.deleteMemberById(memberId)

    /**
     * Creates or updates a bill and recomputes its shares from the chosen split method.
     * Shares are always regenerated rather than patched, so the stored rows can never drift from
     * the bill total.
     */
    suspend fun saveBill(
        bill: Bill,
        participants: List<SplitCalculator.Participant>,
    ): SplitCalculator.Result {
        val billId = groupDao.insertBill(bill.toEntity())
        val effectiveId = if (bill.id == 0L) billId else bill.id

        val split = SplitCalculator.calculate(bill.totalMinor, bill.splitMethod, participants)
        groupDao.deleteSharesForBill(effectiveId)
        groupDao.insertShares(
            split.shares.map { (memberId, shareMinor) ->
                BillShare(
                    billId = effectiveId,
                    memberId = memberId,
                    shareMinor = shareMinor,
                    weight = participants.firstOrNull { it.memberId == memberId }?.weight,
                ).toEntity()
            },
        )
        return split
    }

    suspend fun deleteBill(id: Long) = groupDao.deleteBillById(id)

    suspend fun setBillSettled(billId: Long, settled: Boolean) {
        val bill = groupDao.getBill(billId) ?: return
        groupDao.updateBill(bill.copy(settled = settled))
    }

    suspend fun getBillWithShares(billId: Long): BillWithShares? {
        val bill = groupDao.getBill(billId)?.toDomain() ?: return null
        return BillWithShares(bill, groupDao.getShares(billId).map { it.toDomain() })
    }

    suspend fun sharesByBill(groupId: Long): Map<Long, List<BillShare>> =
        groupDao.getSharesForGroup(groupId).map { it.toDomain() }.groupBy { it.billId }

    /** Net balances for a group with recorded settlements already applied. */
    suspend fun balances(groupId: Long): List<MemberBalance> {
        val members = getMembers(groupId)
        val bills = getBills(groupId)
        return SettlementOptimizer.balances(
            members = members,
            bills = bills,
            sharesByBillId = sharesByBill(groupId),
            settlements = getSettlements(groupId),
        )
    }

    suspend fun settlementPlan(groupId: Long): List<SettlementSuggestion> {
        val group = getGroup(groupId) ?: return emptyList()
        return SettlementOptimizer.suggestSettlements(balances(groupId), group.currency)
    }

    suspend fun recordSettlement(settlement: Settlement): Long =
        groupDao.insertSettlement(settlement.toEntity())

    suspend fun deleteSettlement(id: Long) = groupDao.deleteSettlementById(id)

    suspend fun openBillCount(groupId: Long): Int = groupDao.countOpenBills(groupId)

    /** Convenience for the "who owes what" preview on the dashboard. */
    suspend fun defaultParticipants(groupId: Long, method: SplitMethod): List<SplitCalculator.Participant> {
        val members = getMembers(groupId)
        return when (method) {
            SplitMethod.PERCENTAGE -> {
                val even = if (members.isEmpty()) 0.0 else 100.0 / members.size
                members.map { SplitCalculator.Participant(it.id, even) }
            }
            else -> members.map { SplitCalculator.Participant(it.id, 1.0) }
        }
    }

    private companion object {
        const val DEFAULT_SELF_NAME = "You"
        val MEMBER_COLORS = listOf(
            0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFFE53935,
            0xFF00897B, 0xFF6D4C41, 0xFF3949AB, 0xFFD81B60, 0xFF00ACC1,
        )
    }
}
