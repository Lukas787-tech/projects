package com.expensesplit.app.ui.screens.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.data.repository.RecurringRepository
import com.expensesplit.app.domain.model.RecurringRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecurringUiState(val rules: List<RecurringRule> = emptyList())

@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
) : ViewModel() {

    val uiState: StateFlow<RecurringUiState> = recurringRepository.rules
        .map { RecurringUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    fun setActive(ruleId: Long, active: Boolean) {
        viewModelScope.launch { recurringRepository.setActive(ruleId, active) }
    }

    fun delete(ruleId: Long) {
        viewModelScope.launch { recurringRepository.delete(ruleId) }
    }
}
