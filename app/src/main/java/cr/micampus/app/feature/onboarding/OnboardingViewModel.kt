package cr.micampus.app.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.data.local.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val ucrSelected: Boolean = true,
    val unaSelected: Boolean = true,
    val saving: Boolean = false,
) {
    val canContinue: Boolean get() = ucrSelected || unaSelected
}

class OnboardingViewModel(private val settings: SettingsStore) : ViewModel() {
    private val mutableState = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutableState.asStateFlow()

    fun setUcr(selected: Boolean) = mutableState.update { it.copy(ucrSelected = selected) }
    fun setUna(selected: Boolean) = mutableState.update { it.copy(unaSelected = selected) }

    fun complete(onComplete: () -> Unit) {
        val selection = state.value
        if (!selection.canContinue || selection.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            settings.setOnboarding(true, selection.ucrSelected, selection.unaSelected)
            onComplete()
        }
    }
}
