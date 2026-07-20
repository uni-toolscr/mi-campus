package cr.micampus.app.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.platform.calendar.CalendarChoice
import cr.micampus.app.platform.calendar.CalendarExporter
import cr.micampus.app.platform.calendar.ExportResult
import cr.micampus.app.platform.reminders.ReminderScheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

enum class CalendarPresentation { HORARIO, MONTH, AGENDA }

data class CalendarFilters(
    val excludedInstitutions: Set<Institution> = emptySet(),
    val excludedKinds: Set<EventKind> = setOf(EventKind.CLASS),
    val courseQuery: String = "",
)

internal fun filterAgendaEvents(events: List<CampusEvent>, filters: CalendarFilters): List<CampusEvent> = events.filter { event ->
    event.institution !in filters.excludedInstitutions &&
        event.kind !in filters.excludedKinds &&
        (filters.courseQuery.isBlank() || event.title.contains(filters.courseQuery, true) ||
            event.notes.contains(filters.courseQuery, true) || event.courseCode.orEmpty().contains(filters.courseQuery, true))
}

data class CalendarUiState(
    val loading: Boolean = true,
    val presentation: CalendarPresentation = CalendarPresentation.HORARIO,
    val month: YearMonth = YearMonth.now(),
    val filters: CalendarFilters = CalendarFilters(),
    val events: List<CampusEvent> = emptyList(),
    val agendaEvents: List<CampusEvent> = emptyList(),
    val courseStyles: List<CourseStyle> = emptyList(),
    val message: String? = null,
    val confirmationPending: Boolean = false,
    val enabledInstitutions: List<Institution> = Institution.values().toList(),
    val use12hClock: Boolean = false,
    val semesterStart: LocalDate? = null,
    val semesterEnd: LocalDate? = null,
) {
    /** Sensible default institution for a brand-new event: the single enabled one, or UCR when both/neither are set. */
    val defaultInstitution: Institution get() = enabledInstitutions.singleOrNull() ?: Institution.UCR
}

class CalendarViewModel(
    private val repository: EventRepository,
    private val exporter: CalendarExporter,
    private val reminders: ReminderScheduling,
    private val settings: SettingsStore,
    private val onDataChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val controls = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = combine(repository.confirmedEvents, controls, settings.settings, repository.courseStyles) { events, control, appSettings, courseStyles ->
        val enabled = appSettings.selectedInstitutions().ifEmpty { listOf(Institution.UCR) }
        val agendaFilters = control.filters.copy(
            excludedKinds = appSettings.agendaExcludedKinds,
            excludedInstitutions = appSettings.agendaExcludedInstitutions,
        )
        control.copy(
            loading = false,
            filters = agendaFilters,
            events = events,
            agendaEvents = filterAgendaEvents(events, agendaFilters),
            enabledInstitutions = enabled,
            use12hClock = appSettings.use12hClock,
            semesterStart = appSettings.semesterStart,
            semesterEnd = appSettings.semesterEnd,
            courseStyles = courseStyles,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    fun setPresentation(value: CalendarPresentation) = controls.update { it.copy(presentation = value) }
    fun setMonth(value: YearMonth) = controls.update { it.copy(month = value) }
    fun setCourseQuery(value: String) = controls.update { it.copy(filters = it.filters.copy(courseQuery = value)) }
    fun applyAgendaFilters(excludedKinds: Set<EventKind>, excludedInstitutions: Set<Institution>) = viewModelScope.launch {
        settings.setAgendaFilters(excludedKinds, excludedInstitutions)
        onDataChanged()
    }
    fun clearMessage() = controls.update { it.copy(message = null) }
    fun dismissConfirmation() = controls.update { it.copy(confirmationPending = false) }
    fun saveCourseStyle(style: CourseStyle) = viewModelScope.launch { repository.saveCourseStyle(style) }

    fun save(event: CampusEvent) = viewModelScope.launch {
        require(event.end.isAfter(event.start))
        reminders.cancel(event.id)
        repository.save(event)
        val appSettings = settings.current()
        reminders.schedule(event, appSettings.remindersEnabled, appSettings.exactReminders)
        onDataChanged()
        controls.update { it.copy(message = "Evento guardado") }
    }

    fun saveClassSeries(spec: ClassScheduleSpec) = viewModelScope.launch {
        val events = ClassScheduleGenerator.generate(spec)
        if (events.isEmpty()) {
            controls.update { it.copy(message = "No se generaron sesiones: revisa los días y fechas del semestre") }
            return@launch
        }
        repository.saveAll(events)
        settings.setSemesterRange(spec.semesterStart, spec.semesterEnd)
        val appSettings = settings.current()
        events.forEach { event ->
            reminders.cancel(event.id)
            reminders.schedule(event, appSettings.remindersEnabled, appSettings.exactReminders)
        }
        onDataChanged()
        controls.update { it.copy(message = "Se crearon ${events.size} sesiones de clase") }
    }

    /**
     * Deletes every session of a weekly class slot (same title, day of week, and start/end time).
     * This intentionally mirrors the grouping in [deriveScheduleSlots]: whatever renders into the
     * tapped Horario block is what gets removed, regardless of source or semester.
     */
    fun deleteClassSeries(title: String, day: DayOfWeek, start: LocalTime, end: LocalTime) = viewModelScope.launch {
        // Match against the unfiltered repository list so active UI filters can't hide sessions from the delete.
        val matches = repository.confirmedEvents.first().filter {
            it.kind == EventKind.CLASS && it.title.trim() == title && it.start.dayOfWeek == day &&
                it.start.toLocalTime() == start && it.end.toLocalTime() == end
        }
        matches.forEach { event ->
            reminders.cancel(event.id)
            repository.delete(event)
        }
        onDataChanged()
        controls.update { it.copy(message = "Se eliminaron ${matches.size} sesiones de clase") }
    }

    fun delete(event: CampusEvent) = viewModelScope.launch {
        reminders.cancel(event.id)
        repository.delete(event)
        onDataChanged()
        controls.update { it.copy(message = "Evento eliminado") }
    }

    fun deleteSelected(ids: Set<String>) = viewModelScope.launch {
        val matches = repository.confirmedEvents.first().filter { it.id in ids }
        matches.forEach { reminders.cancel(it.id) }
        repository.deleteAll(matches)
        if (matches.isNotEmpty()) onDataChanged()
        controls.update { it.copy(message = "Se eliminaron ${matches.size} eventos") }
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
