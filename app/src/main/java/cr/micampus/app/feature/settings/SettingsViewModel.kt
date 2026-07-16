package cr.micampus.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.data.ai.ApiKeyStore
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.core.model.ReminderSettings
import cr.micampus.app.platform.reminders.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keyPresent: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(
    private val store: SettingsStore,
    private val keys: ApiKeyStore,
    private val events: EventRepository,
    private val reminders: ReminderScheduler,
) : ViewModel() {
    private val keyPresent = MutableStateFlow(keys.read() != null)
    private val message = MutableStateFlow<String?>(null)
    val state: StateFlow<SettingsUiState> = combine(store.settings, keyPresent, message) { settings, hasKey, text ->
        SettingsUiState(settings, hasKey, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setInstitutions(ucr: Boolean, una: Boolean) {
        if (!ucr && !una) {
            message.value = "Selecciona al menos una institución"
            return
        }
        viewModelScope.launch { store.setOnboarding(true, ucr, una) }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { store.setTheme(mode) }
    fun setReminders(enabled: Boolean) = viewModelScope.launch {
        store.setReminders(enabled)
        reschedule(enabled, store.current().exactReminders)
    }

    fun setExactReminders(enabled: Boolean) = viewModelScope.launch {
        store.setExactReminders(enabled)
        reschedule(store.current().remindersEnabled, enabled)
    }
    fun setAi(enabled: Boolean) = viewModelScope.launch { store.setAi(enabled) }

    fun saveKey(value: String) {
        runCatching { keys.save(value.trim()) }
            .onSuccess { keyPresent.value = true; message.value = "Clave guardada de forma cifrada" }
            .onFailure { message.value = "No se pudo guardar la clave" }
    }

    fun clearKey() {
        keys.clear()
        keyPresent.value = false
        message.value = "Clave eliminada"
    }

    fun clearMessage() { message.value = null }

    private suspend fun reschedule(enabled: Boolean, exact: Boolean) {
        events.futureEntities().map(EventRepository::toDomain).forEach { event ->
            reminders.cancel(event.id)
            if (enabled) reminders.schedule(event, ReminderSettings(enabled = true), exact)
        }
    }
}
