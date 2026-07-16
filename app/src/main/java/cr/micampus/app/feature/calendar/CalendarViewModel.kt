package cr.micampus.app.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.platform.calendar.CalendarChoice
import cr.micampus.app.platform.calendar.CalendarExporter
import cr.micampus.app.platform.calendar.ExportResult
import cr.micampus.app.platform.reminders.ReminderScheduler
import cr.micampus.app.core.model.ReminderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

enum class CalendarPresentation { MONTH, AGENDA }

data class CalendarFilters(
    val institution: Institution? = null,
    val kind: EventKind? = null,
    val courseQuery: String = "",
)

data class CalendarUiState(
    val loading: Boolean = true,
    val presentation: CalendarPresentation = CalendarPresentation.AGENDA,
    val month: YearMonth = YearMonth.now(),
    val filters: CalendarFilters = CalendarFilters(),
    val events: List<CampusEvent> = emptyList(),
    val message: String? = null,
    val confirmationPending: Boolean = false,
    val enabledInstitutions: List<Institution> = Institution.values().toList(),
) {
    /** Sensible default institution for a brand-new event: the single enabled one, or UCR when both/neither are set. */
    val defaultInstitution: Institution get() = enabledInstitutions.singleOrNull() ?: Institution.UCR
}

class CalendarViewModel(
    private val repository: EventRepository,
    private val exporter: CalendarExporter,
    private val reminders: ReminderScheduler,
    private val settings: SettingsStore,
    private val onDataChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val controls = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = combine(repository.confirmedEvents, controls, settings.settings) { events, control, appSettings ->
        val enabled = buildList {
            if (appSettings.ucrEnabled) add(Institution.UCR)
            if (appSettings.unaEnabled) add(Institution.UNA)
        }.ifEmpty { listOf(Institution.UCR) }
        control.copy(
            loading = false,
            events = events.filter { event ->
                (control.filters.institution == null || event.institution == control.filters.institution) &&
                    (control.filters.kind == null || event.kind == control.filters.kind) &&
                    (control.filters.courseQuery.isBlank() || event.title.contains(control.filters.courseQuery, true) || event.notes.contains(control.filters.courseQuery, true))
            },
            enabledInstitutions = enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun setPresentation(value: CalendarPresentation) = controls.update { it.copy(presentation = value) }
    fun setMonth(value: YearMonth) = controls.update { it.copy(month = value) }
    fun setInstitution(value: Institution?) = controls.update { it.copy(filters = it.filters.copy(institution = value)) }
    fun setKind(value: EventKind?) = controls.update { it.copy(filters = it.filters.copy(kind = value)) }
    fun setCourseQuery(value: String) = controls.update { it.copy(filters = it.filters.copy(courseQuery = value)) }
    fun clearMessage() = controls.update { it.copy(message = null) }
    fun dismissConfirmation() = controls.update { it.copy(confirmationPending = false) }

    fun save(event: CampusEvent) = viewModelScope.launch {
        require(event.end.isAfter(event.start))
        reminders.cancel(event.id)
        repository.save(event)
        val appSettings = settings.current()
        reminders.schedule(event, ReminderSettings(enabled = appSettings.remindersEnabled), appSettings.exactReminders)
        onDataChanged()
        controls.update { it.copy(message = "Evento guardado") }
    }

    fun delete(event: CampusEvent) = viewModelScope.launch {
        reminders.cancel(event.id)
        repository.delete(event)
        onDataChanged()
        controls.update { it.copy(message = "Evento eliminado") }
    }

    fun writableCalendars(): List<CalendarChoice> = exporter.writableCalendars()

    fun exportAll(calendarId: Long, confirmUpdates: Boolean) = viewModelScope.launch {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var confirmationNeeded = false
        for (event in state.value.events) {
            val previous = repository.exportRecord(event.id, calendarId.toString())
            when (val result = exporter.export(event, calendarId, previous, confirmUpdates)) {
                is ExportResult.Exported -> {
                    repository.saveExportRecord(result.record)
                    if (result.updated) updated++ else inserted++
                }
                ExportResult.Unchanged -> unchanged++
                ExportResult.ConfirmationRequired -> confirmationNeeded = true
                ExportResult.PermissionRequired -> {
                    controls.update { it.copy(message = "Permiso de calendario requerido") }
                    return@launch
                }
                is ExportResult.Failed -> controls.update { it.copy(message = result.message) }
            }
        }
        controls.update {
            it.copy(
                confirmationPending = confirmationNeeded,
                message = if (confirmationNeeded) "Hay eventos modificados. Confirma antes de actualizarlos." else "$inserted nuevos, $updated actualizados, $unchanged sin cambios",
            )
        }
    }
}
