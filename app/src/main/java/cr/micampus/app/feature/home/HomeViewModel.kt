package cr.micampus.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.data.institution.upcomingDepartures
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDateTime

internal fun homeUpcomingEvents(events: List<CampusEvent>, now: LocalDateTime, limit: Int = 5): List<CampusEvent> =
    events.filter { it.kind != cr.micampus.app.core.model.EventKind.CLASS && !it.end.isBefore(now) }.take(limit)

data class HomeUiState(
    val loading: Boolean = true,
    val events: List<CampusEvent> = emptyList(),
    val buses: List<UpcomingDeparture> = emptyList(),
    val use12hClock: Boolean = false,
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
            events = homeUpcomingEvents(allEvents, now),
            buses = nextBuses(appSettings, now),
            use12hClock = appSettings.use12hClock,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun nextBuses(settings: AppSettings, now: LocalDateTime): List<UpcomingDeparture> {
        val institutions = settings.selectedInstitutions()
        return transport.upcomingDepartures(institutions, now, limit = 4)
    }
}
