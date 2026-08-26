package com.expensesplit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expensesplit.app.data.preferences.PreferencesRepository
import com.expensesplit.app.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Holds the preferences the whole app shell needs: theme, dynamic colour, onboarding state. */
@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences?> = preferencesRepository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // null means "not loaded yet", which keeps the splash screen up rather than flashing
            // the light theme before the stored dark preference arrives.
            initialValue = null,
        )
}
