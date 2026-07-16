package cr.micampus.app.feature.transport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportDirection
import cr.micampus.app.core.model.TransportService
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.local.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class TransportUiState(
    val institution: Institution = Institution.UCR,
    val dataset: TransportDataset? = null,
    val directions: List<TransportDirection> = emptyList(),
    val selectedDirectionId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val service: TransportService? = null,
    val nextDeparture: LocalDateTime? = null,
    val minutesUntil: Long? = null,
)

class TransportViewModel(
    private val repository: AssetTransportRepository,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    // Both institutions are always selectable on this screen (schedules merge UCR/UNA); the
    // initial selection only follows the enabled institution when the student picked just one.
    private val mutableState = MutableStateFlow(buildState(Institution.UCR, null, LocalDate.now(clock)))
    val state: StateFlow<TransportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val appSettings = settings.current()
            val initial = when {
                appSettings.ucrEnabled && !appSettings.unaEnabled -> Institution.UCR
                appSettings.unaEnabled && !appSettings.ucrEnabled -> Institution.UNA
                else -> Institution.UCR
            }
            if (initial != mutableState.value.institution) {
                mutableState.value = buildState(initial, null, mutableState.value.date)
            }
        }
    }

    fun selectInstitution(institution: Institution) {
        mutableState.value = buildState(institution, null, state.value.date)
    }

    fun selectDirection(directionId: String) {
        mutableState.value = buildState(state.value.institution, directionId, state.value.date)
    }

    fun selectDate(date: LocalDate) {
        mutableState.value = buildState(state.value.institution, state.value.selectedDirectionId, date)
    }

    fun refreshCountdown() {
        mutableState.update { current -> buildState(current.institution, current.selectedDirectionId, current.date) }
    }

    private fun buildState(institution: Institution, requestedDirection: String?, date: LocalDate): TransportUiState {
        val directions = repository.directions(institution)
        val selected = directions.firstOrNull { it.id == requestedDirection } ?: directions.firstOrNull()
        val service = selected?.let { repository.service(institution, it.id, date) }
        val now = LocalDateTime.now(clock)
        val next = if (service?.status == ServiceStatus.VERIFIED && date == now.toLocalDate()) {
            service.departures.map(LocalTime::parse).firstOrNull { it.isAfter(now.toLocalTime()) }?.let { LocalDateTime.of(date, it) }
        } else null
        return TransportUiState(
            institution = institution,
            dataset = repository.dataset(institution),
            directions = directions,
            selectedDirectionId = selected?.id,
            date = date,
            service = service,
            nextDeparture = next,
            minutesUntil = next?.let { Duration.between(now, it).toMinutes().coerceAtLeast(0) },
        )
    }
}
