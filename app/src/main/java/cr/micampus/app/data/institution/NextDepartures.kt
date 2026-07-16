package cr.micampus.app.data.institution

import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import java.time.LocalDateTime
import java.time.LocalTime

data class UpcomingDeparture(
    val institution: Institution,
    val directionId: String,
    val from: String,
    val to: String,
    val departure: LocalDateTime,
)

/**
 * Next verified departures for today across [institutions], one per direction,
 * sorted by departure time. Departures already past [now] are skipped.
 */
fun AssetTransportRepository.upcomingDepartures(
    institutions: Collection<Institution>,
    now: LocalDateTime,
    limit: Int = 4,
): List<UpcomingDeparture> = institutions.flatMap { institution ->
    directions(institution).mapNotNull { direction ->
        val service = service(institution, direction.id, now.toLocalDate())
        if (service.status != ServiceStatus.VERIFIED) return@mapNotNull null
        service.departures
            .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
            .firstOrNull { it.isAfter(now.toLocalTime()) }
            ?.let { UpcomingDeparture(institution, direction.id, direction.from, direction.to, LocalDateTime.of(now.toLocalDate(), it)) }
    }
}.sortedBy { it.departure }.take(limit)
