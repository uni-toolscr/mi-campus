package cr.micampus.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime

data class NextBus(val institution: Institution, val direction: String, val departure: LocalDateTime)
data class HomeUiState(
    val loading: Boolean = true,
    val events: List<CampusEvent> = emptyList(),
    val buses: List<NextBus> = emptyList(),
)

class HomeViewModel(
    events: EventRepository,
    settings: SettingsStore,
    private val transport: AssetTransportRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(events.confirmedEvents, settings.settings) { allEvents, appSettings ->
        val now = LocalDateTime.now(clock)
        HomeUiState(
            loading = false,
            events = allEvents.filter { !it.end.isBefore(now) }.take(5),
            buses = nextBuses(appSettings, now),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun nextBuses(settings: AppSettings, now: LocalDateTime): List<NextBus> {
        val institutions = buildList {
            if (settings.ucrEnabled) add(Institution.UCR)
            if (settings.unaEnabled) add(Institution.UNA)
        }
        return institutions.mapNotNull { institution ->
            val direction = transport.directions(institution).firstOrNull() ?: return@mapNotNull null
            val service = transport.service(institution, direction.id, now.toLocalDate())
            if (service.status != ServiceStatus.VERIFIED) return@mapNotNull null
            val time = service.departures.map(LocalTime::parse).firstOrNull { it.isAfter(now.toLocalTime()) } ?: return@mapNotNull null
            NextBus(institution, "${direction.from} → ${direction.to}", LocalDateTime.of(now.toLocalDate(), time))
        }.sortedBy { it.departure }
    }
}
