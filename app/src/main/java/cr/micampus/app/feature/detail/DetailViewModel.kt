package cr.micampus.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.platform.reminders.ReminderScheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val events: List<CampusEvent> = emptyList(),
    val courseStyles: List<CourseStyle> = emptyList(),
    val use12hClock: Boolean = false,
    val remindersEnabled: Boolean = false,
    val enabledInstitutions: List<Institution> = Institution.entries,
)

/** A snackbar the shell shows; [undo] restores what the action changed. */
data class DetailMessage(val text: String, val undo: (() -> Unit)? = null)

/**
 * Actions on a single event or course, shared by Inicio, the event page and the course sheet so
 * marking a task done or editing an exam behaves the same wherever it starts.
 */
class DetailViewModel(
    private val repository: EventRepository,
    private val reminders: ReminderScheduling,
    private val settings: SettingsStore,
    private val onDataChanged: suspend () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<DetailUiState> = combine(repository.confirmedEvents, repository.courseStyles, settings.settings) { events, styles, appSettings ->
        DetailUiState(
            events = events,
            courseStyles = styles,
            use12hClock = appSettings.use12hClock,
            remindersEnabled = appSettings.remindersEnabled,
            enabledInstitutions = appSettings.selectedInstitutions().ifEmpty { listOf(Institution.UCR) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private val messageChannel = Channel<DetailMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun setCompleted(event: CampusEvent, completed: Boolean, announce: Boolean = true): Job = viewModelScope.launch {
        repository.setCompleted(event.id, completed)
        reminders.cancel(event.id)
        if (!completed) reschedule(event.copy(completed = false))
        onDataChanged()
        if (announce && completed) {
            messageChannel.send(DetailMessage("${event.title} hecha") { setCompleted(event, completed = false, announce = false) })
        }
    }

    fun setThirtyMinuteReminder(event: CampusEvent, enabled: Boolean) = save(event.copy(notifyThirtyMinutesBefore = enabled), announce = false)

    fun save(event: CampusEvent, announce: Boolean = true): Job = viewModelScope.launch {
        require(event.end.isAfter(event.start))
        reminders.cancel(event.id)
        repository.save(event)
        reschedule(event)
        onDataChanged()
        if (announce) messageChannel.send(DetailMessage("Evento guardado"))
    }

    fun delete(event: CampusEvent) = viewModelScope.launch {
        reminders.cancel(event.id)
        repository.delete(event)
        onDataChanged()
        messageChannel.send(DetailMessage("Evento eliminado") { save(event, announce = false) })
    }

    /** Removes every session of the course, whichever weekly block or semester it came from. */
    fun deleteCourse(key: String) = viewModelScope.launch {
        val sessions = repository.confirmedEvents.first().filter { it.kind == EventKind.CLASS && it.title.trim() == key }
        sessions.forEach { reminders.cancel(it.id) }
        repository.deleteAll(sessions)
        onDataChanged()
        messageChannel.send(DetailMessage("Se eliminó $key (${sessions.size} sesiones)"))
    }

    fun saveCourseStyle(style: CourseStyle) = viewModelScope.launch {
        repository.saveCourseStyle(style)
        onDataChanged()
    }

    private suspend fun reschedule(event: CampusEvent) {
        val appSettings = settings.current()
        reminders.schedule(event, appSettings.remindersEnabled, appSettings.exactReminders)
    }
}
