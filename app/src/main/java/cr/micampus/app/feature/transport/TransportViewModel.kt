package cr.micampus.app.feature.transport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportDirection
import cr.micampus.app.core.model.TransportService
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.institution.serviceDays
import cr.micampus.app.data.local.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class TransportUiState(
    val institution: Institution = Institution.UCR,
    val dataset: TransportDataset? = null,
    val directions: List<TransportDirection> = emptyList(),
    val selectedDirectionId: String? = null,
    /** Day the student is browsing; null follows the service day of the next departure. */
    val date: LocalDate? = null,
    /** Service for [date]; null while following the next departure. */
    val service: TransportService? = null,
    /** Today's service plus the next verified service days, where the hero finds its departure. */
    val serviceDays: List<TransportService> = emptyList(),
    val use12hClock: Boolean = false,
)

class TransportViewModel(
    private val repository: AssetTransportRepository,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    // Both institutions are always selectable on this screen (schedules merge UCR/UNA); the
    // initial selection only follows the enabled institution when the student picked just one.
    private val mutableState = MutableStateFlow(buildState(Institution.UCR, null, null))
    val state: StateFlow<TransportUiState> = mutableState.asStateFlow()

    // Set once anything picks a route, so the settings-based default can't overwrite it.
    private var explicitSelection = false

    init {
        viewModelScope.launch {
            val appSettings = settings.current()
            mutableState.update { it.copy(use12hClock = appSettings.use12hClock) }
            val initial = appSettings.selectedInstitutions().singleOrNull() ?: Institution.UCR
            if (!explicitSelection && initial != mutableState.value.institution) {
                rebuild(initial, null, mutableState.value.date)
            }
        }
        viewModelScope.launch {
            settings.settings.collect { appSettings -> mutableState.update { it.copy(use12hClock = appSettings.use12hClock) } }
        }
    }

    /** Opens [directionId] of [institution] on its next departure, e.g. from the Inicio bus card. */
    fun select(institution: Institution, directionId: String) {
        explicitSelection = true
        rebuild(institution, directionId, null)
    }

    fun selectInstitution(institution: Institution) {
        explicitSelection = true
        rebuild(institution, null, state.value.date)
    }

    fun selectDirection(directionId: String) {
        explicitSelection = true
        rebuild(state.value.institution, directionId, state.value.date)
    }

    fun selectDate(date: LocalDate) {
        rebuild(state.value.institution, state.value.selectedDirectionId, date)
    }

    private fun rebuild(institution: Institution, directionId: String?, date: LocalDate?) {
        mutableState.value = buildState(institution, directionId, date).copy(use12hClock = state.value.use12hClock)
    }

    private fun buildState(institution: Institution, requestedDirection: String?, date: LocalDate?): TransportUiState {
        val directions = repository.directions(institution)
        val selected = directions.firstOrNull { it.id == requestedDirection } ?: directions.firstOrNull()
        return TransportUiState(
            institution = institution,
            dataset = repository.dataset(institution),
            directions = directions,
            selectedDirectionId = selected?.id,
            date = date,
            service = if (selected != null && date != null) repository.service(institution, selected.id, date) else null,
            serviceDays = selected?.let { repository.serviceDays(institution, it.id, LocalDate.now(clock)) }.orEmpty(),
        )
    }
}
