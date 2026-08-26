package com.expensesplit.app.ui.screens.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.core.Money
import com.expensesplit.app.data.export.CsvExporter
import com.expensesplit.app.data.export.FileSharer
import com.expensesplit.app.data.repository.GroupRepository
import com.expensesplit.app.domain.model.Bill
import com.expensesplit.app.domain.model.ExpenseGroup
import com.expensesplit.app.domain.model.Member
import com.expensesplit.app.domain.model.MemberBalance
import com.expensesplit.app.domain.model.Settlement
import com.expensesplit.app.domain.model.SettlementSuggestion
import com.expensesplit.app.domain.model.SplitMethod
import com.expensesplit.app.domain.split.SplitCalculator
import com.expensesplit.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

data class GroupDetailUiState(
    val group: ExpenseGroup? = null,
    val members: List<Member> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val balances: List<MemberBalance> = emptyList(),
    val settlementPlan: List<SettlementSuggestion> = emptyList(),
    val settlements: List<Settlement> = emptyList(),
    val isLoading: Boolean = true,
) {
    val currency: String get() = group?.currency ?: "USD"
    val isSettledUp: Boolean get() = balances.isNotEmpty() && balances.all { it.netMinor == 0L }
    fun memberName(id: Long): String = members.firstOrNull { it.id == id }?.name.orEmpty()
}

/** Live state of the add-bill sheet, recalculated on every keystroke so shares stay in sync. */
data class BillDraft(
    val title: String = "",
    val totalText: String = "",
    val paidByMemberId: Long = 0,
    val date: LocalDate = LocalDate.now(),
    val splitMethod: SplitMethod = SplitMethod.EQUAL,
    val note: String = "",
    /** memberId -> weight: a percentage, a share count, or a literal amount for CUSTOM. */
    val weights: Map<Long, String> = emptyMap(),
    val includedMemberIds: Set<Long> = emptySet(),
    val computedShares: Map<Long, Long> = emptyMap(),
    val warnings: List<SplitCalculator.Warning> = emptyList(),
    val editingBillId: Long = 0,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val csvExporter: CsvExporter,
    private val fileSharer: FileSharer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: Long = savedStateHandle.get<String>(Routes.ARG_GROUP_ID)?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private val _draft = MutableStateFlow(BillDraft())
    val draft: StateFlow<BillDraft> = _draft.asStateFlow()

    private val _exportedFile = MutableStateFlow<File?>(null)
    val exportedFile: StateFlow<File?> = _exportedFile.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            // Loaded up front rather than inside `update`, whose lambda runs in a compare-and-set
            // loop and would otherwise re-query the database on contention.
            val group = groupRepository.getGroup(groupId)
            val members = groupRepository.getMembers(groupId)
            val bills = groupRepository.getBills(groupId)
            val balances = groupRepository.balances(groupId)
            val plan = groupRepository.settlementPlan(groupId)
            val settlements = groupRepository.getSettlements(groupId)

            _uiState.update {
                it.copy(
                    group = group,
                    members = members,
                    bills = bills,
                    balances = balances,
                    settlementPlan = plan,
                    settlements = settlements,
                    isLoading = false,
                )
            }
            // Default a new bill to "everyone, paid by me".
            if (_draft.value.paidByMemberId == 0L) {
                _draft.update { draft ->
                    draft.copy(
                        paidByMemberId = members.firstOrNull { it.isSelf }?.id
                            ?: members.firstOrNull()?.id ?: 0L,
                        includedMemberIds = members.map { it.id }.toSet(),
                    )
                }
                recalculateSplit()
            }
        }
    }

    fun startNewBill() {
        val members = _uiState.value.members
        _draft.value = BillDraft(
            paidByMemberId = members.firstOrNull { it.isSelf }?.id ?: members.firstOrNull()?.id ?: 0L,
            includedMemberIds = members.map { it.id }.toSet(),
        )
        recalculateSplit()
    }

    fun startEditingBill(billId: Long) {
        viewModelScope.launch {
            val bundle = groupRepository.getBillWithShares(billId) ?: return@launch
            val bill = bundle.bill
            _draft.value = BillDraft(
                title = bill.title,
                totalText = Money.toEditableString(bill.totalMinor, bill.currency),
                paidByMemberId = bill.paidByMemberId,
                date = bill.date,
                splitMethod = bill.splitMethod,
                note = bill.note.orEmpty(),
                weights = bundle.shares.associate { share ->
                    share.memberId to when (bill.splitMethod) {
                        SplitMethod.CUSTOM -> Money.toEditableString(share.shareMinor, bill.currency)
                        else -> (share.weight ?: 1.0).toString()
                    }
                },
                includedMemberIds = bundle.shares.map { it.memberId }.toSet(),
                editingBillId = billId,
            )
            recalculateSplit()
        }
    }

    fun onTitleChanged(value: String) = _draft.update { it.copy(title = value) }

    fun onNoteChanged(value: String) = _draft.update { it.copy(note = value) }

    fun onTotalChanged(value: String) {
        _draft.update { it.copy(totalText = value) }
        recalculateSplit()
    }

    fun onPaidByChanged(memberId: Long) = _draft.update { it.copy(paidByMemberId = memberId) }

    fun onDateChanged(date: LocalDate) = _draft.update { it.copy(date = date) }

    fun onSplitMethodChanged(method: SplitMethod) {
        _draft.update { draft ->
            val members = _uiState.value.members.filter { it.id in draft.includedMemberIds }
            // Seed sensible starting weights so the sheet is never in an invalid state.
            val seeded = when (method) {
                SplitMethod.PERCENTAGE -> {
                    val even = if (members.isEmpty()) 0.0 else 100.0 / members.size
                    members.associate { it.id to String.format("%.1f", even) }
                }
                SplitMethod.SHARES -> members.associate { it.id to "1" }
                SplitMethod.CUSTOM -> members.associate { it.id to "" }
                SplitMethod.EQUAL -> emptyMap()
            }
            draft.copy(splitMethod = method, weights = seeded)
        }
        recalculateSplit()
    }

    fun onWeightChanged(memberId: Long, value: String) {
        _draft.update { it.copy(weights = it.weights + (memberId to value)) }
        recalculateSplit()
    }

    fun toggleMember(memberId: Long) {
        _draft.update { draft ->
            val included = if (memberId in draft.includedMemberIds) {
                draft.includedMemberIds - memberId
            } else {
                draft.includedMemberIds + memberId
            }
            draft.copy(includedMemberIds = included)
        }
        recalculateSplit()
    }

    /**
     * Recomputes every share from the current draft.
     *
     * Running on each edit means the sheet always shows the real, rounding-exact result rather than
     * a preview that differs from what gets saved.
     */
    private fun recalculateSplit() {
        val draft = _draft.value
        val currency = _uiState.value.currency
        val totalMinor = Money.parseToMinor(draft.totalText, currency) ?: 0L
        val members = _uiState.value.members.filter { it.id in draft.includedMemberIds }

        if (totalMinor <= 0 || members.isEmpty()) {
            _draft.update { it.copy(computedShares = emptyMap(), warnings = emptyList()) }
            return
        }

        val participants = members.map { member ->
            val raw = draft.weights[member.id].orEmpty()
            val weight = when (draft.splitMethod) {
                SplitMethod.EQUAL -> 1.0
                SplitMethod.CUSTOM -> (Money.parseToMinor(raw, currency) ?: 0L).toDouble()
                else -> raw.replace(',', '.').toDoubleOrNull() ?: 0.0
            }
            SplitCalculator.Participant(member.id, weight)
        }

        val result = SplitCalculator.calculate(totalMinor, draft.splitMethod, participants)
        _draft.update { it.copy(computedShares = result.shares, warnings = result.warnings) }
    }

    fun saveBill() {
        val draft = _draft.value
        val state = _uiState.value
        val currency = state.currency
        val totalMinor = Money.parseToMinor(draft.totalText, currency) ?: return
        if (totalMinor <= 0 || draft.title.isBlank() || draft.includedMemberIds.isEmpty()) return

        viewModelScope.launch {
            val members = state.members.filter { it.id in draft.includedMemberIds }
            val participants = members.map { member ->
                val raw = draft.weights[member.id].orEmpty()
                val weight = when (draft.splitMethod) {
                    SplitMethod.EQUAL -> 1.0
                    SplitMethod.CUSTOM -> (Money.parseToMinor(raw, currency) ?: 0L).toDouble()
                    else -> raw.replace(',', '.').toDoubleOrNull() ?: 0.0
                }
                SplitCalculator.Participant(member.id, weight)
            }

            groupRepository.saveBill(
                bill = Bill(
                    id = draft.editingBillId,
                    groupId = groupId,
                    title = draft.title.trim(),
                    totalMinor = totalMinor,
                    currency = currency,
                    paidByMemberId = draft.paidByMemberId,
                    date = draft.date,
                    splitMethod = draft.splitMethod,
                    note = draft.note.trim().takeIf { it.isNotBlank() },
                ),
                participants = participants,
            )
            startNewBill()
            reload()
        }
    }

    fun deleteBill(billId: Long) {
        viewModelScope.launch {
            groupRepository.deleteBill(billId)
            reload()
        }
    }

    fun addMember(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            groupRepository.addMember(groupId, name)
            reload()
        }
    }

    fun removeMember(memberId: Long) {
        viewModelScope.launch {
            groupRepository.removeMember(memberId)
            reload()
        }
    }

    /** Records that a suggested transfer actually happened, which clears it from the plan. */
    fun recordSettlement(suggestion: SettlementSuggestion) {
        viewModelScope.launch {
            groupRepository.recordSettlement(
                Settlement(
                    groupId = groupId,
                    fromMemberId = suggestion.fromMemberId,
                    toMemberId = suggestion.toMemberId,
                    amountMinor = suggestion.amountMinor,
                    currency = suggestion.currency,
                ),
            )
            reload()
        }
    }

    fun undoSettlement(settlementId: Long) {
        viewModelScope.launch {
            groupRepository.deleteSettlement(settlementId)
            reload()
        }
    }

    fun exportSummary() {
        viewModelScope.launch {
            val state = _uiState.value
            val group = state.group ?: return@launch
            _exportedFile.value = csvExporter.exportSettlements(
                groupName = group.name,
                currency = group.currency,
                members = state.members,
                bills = state.bills,
                suggestions = state.settlementPlan,
            )
        }
    }

    fun shareIntentFor(file: File) = fileSharer.shareIntent(file)

    /** Plain-text settlement summary, for pasting into a group chat. */
    fun shareSummaryText(): String {
        val state = _uiState.value
        val group = state.group ?: return ""
        return buildString {
            appendLine(group.name)
            appendLine()
            state.settlementPlan.forEach { suggestion ->
                appendLine(
                    "${state.memberName(suggestion.fromMemberId)} → " +
                        "${state.memberName(suggestion.toMemberId)}: " +
                        Money.format(suggestion.amountMinor, suggestion.currency),
                )
            }
            if (state.settlementPlan.isEmpty()) appendLine("All settled up.")
        }
    }

    fun shareTextIntent(text: String) = fileSharer.shareTextIntent(text)

    fun onExportHandled() {
        _exportedFile.value = null
    }
}
