package com.expensesplit.app.ui.screens.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.repository.CurrencyRepository
import com.expensesplit.app.data.repository.GroupRepository
import com.expensesplit.app.domain.model.ExpenseGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the group list, with the balance already resolved from the member's point of view. */
data class GroupSummary(
    val group: ExpenseGroup,
    val memberCount: Int,
    val billCount: Int,
    val openBillCount: Int,
    val yourNetMinor: Long,
)

data class BillsUiState(
    val groups: List<GroupSummary> = emptyList(),
    val baseCurrency: String = "USD",
    val currencies: List<String> = emptyList(),
    val selfName: String = "",
    val isLoading: Boolean = true,
)

@HiltViewModel
class BillsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val currencyRepository: CurrencyRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)
    private val _createdGroupId = MutableStateFlow<Long?>(null)
    val createdGroupId: StateFlow<Long?> = _createdGroupId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BillsUiState> = combine(
        groupRepository.groups,
        preferencesRepository.preferences,
        refreshTrigger,
    ) { groups, preferences, _ ->
        groups to preferences
    }.mapLatest { (groups, preferences) ->
        BillsUiState(
            groups = groups.map { group ->
                val balances = groupRepository.balances(group.id)
                GroupSummary(
                    group = group,
                    memberCount = balances.size,
                    billCount = groupRepository.getBills(group.id).size,
                    openBillCount = groupRepository.openBillCount(group.id),
                    yourNetMinor = balances.firstOrNull { it.member.isSelf }?.netMinor ?: 0L,
                )
            },
            baseCurrency = preferences.baseCurrency,
            currencies = currencyRepository.supportedCurrencies,
            selfName = preferences.selfDisplayName,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BillsUiState(),
    )

    fun createGroup(name: String, currency: String, memberNames: List<String>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val preferences = preferencesRepository.preferences.first()
            val selfName = preferences.selfDisplayName.ifBlank { DEFAULT_SELF_NAME }
            _createdGroupId.value = groupRepository.createGroup(
                name = name.trim(),
                currency = currency,
                selfName = selfName,
                memberNames = memberNames,
            )
            refreshTrigger.value += 1
        }
    }

    fun deleteGroup(groupId: Long) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId)
            refreshTrigger.value += 1
        }
    }

    fun onGroupOpened() {
        _createdGroupId.value = null
    }

    fun refresh() = refreshTrigger.update { it + 1 }

    private companion object {
        const val DEFAULT_SELF_NAME = "You"
    }
}
