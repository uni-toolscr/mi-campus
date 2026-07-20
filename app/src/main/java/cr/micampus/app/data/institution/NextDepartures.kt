package cr.micampus.app.data.institution

import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import java.time.Duration
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
 * Composite, institution-scoped key for a direction, used to persist which
 * directions a bus widget should show (UCR and UNA direction ids never collide).
 */
fun directionKey(institution: Institution, directionId: String): String = "${institution.name}|$directionId"

/**
 * Next verified departures for today across [institutions], one per direction,
 * sorted by departure time. Departures already past [now] are skipped. A
 * caller can retain a departure for a short [departureGrace] period; this is
 * useful for the widget's "Saliendo ahora" state without changing the default
 * behaviour used by the app screens.
 *
 * When [directionIds] is non-null and non-empty, only directions whose
 * [directionKey] is in the set are kept; null/empty means every direction.
 */
fun AssetTransportRepository.upcomingDepartures(
    institutions: Collection<Institution>,
    now: LocalDateTime,
    limit: Int = 4,
    directionIds: Set<String>? = null,
    departureGrace: Duration = Duration.ZERO,
): List<UpcomingDeparture> {
    require(!departureGrace.isNegative)
    return institutions.flatMap { institution ->
        directions(institution)
            .filter { directionIds.isNullOrEmpty() || directionKey(institution, it.id) in directionIds }
            .mapNotNull { direction ->
                val service = service(institution, direction.id, now.toLocalDate())
                if (service.status != ServiceStatus.VERIFIED) return@mapNotNull null
                service.departures
                    .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
                    .map { LocalDateTime.of(now.toLocalDate(), it) }
                    .firstOrNull { it.isAfter(now.minus(departureGrace)) }
                    ?.let { UpcomingDeparture(institution, direction.id, direction.from, direction.to, it) }
            }
    }.sortedBy { it.departure }.take(limit)
}
