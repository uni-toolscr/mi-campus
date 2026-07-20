package cr.micampus.app.data.institution

import android.content.Context
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportDirection
import cr.micampus.app.core.model.TransportService
import java.time.DayOfWeek
import java.time.LocalDate

class AssetTransportRepository(private val datasets: Map<Institution, TransportDataset>) {
    fun dataset(institution: Institution): TransportDataset? = datasets[institution]

    fun directions(institution: Institution): List<TransportDirection> = datasets[institution]?.directions.orEmpty()

    fun service(institution: Institution, directionId: String, date: LocalDate): TransportService {
        val dataset = datasets[institution] ?: return TransportService(ServiceStatus.UNKNOWN, null, date, emptyList())
        val resolved = resolve(dataset, directionId, date)
        return if (resolved.status == ServiceStatus.NO_SERVICE) {
            resolved.copy(nextValidDate = nextValidDate(institution, directionId, date.plusDays(1)))
        } else resolved
    }

    fun nextValidDate(institution: Institution, directionId: String, from: LocalDate): LocalDate? {
        val dataset = datasets[institution] ?: return null
        return (0L..370L).asSequence().map(from::plusDays)
            .firstOrNull { resolve(dataset, directionId, it).status == ServiceStatus.VERIFIED }
    }

    private fun resolve(dataset: TransportDataset, directionId: String, date: LocalDate): TransportService {
        if (date.isBefore(dataset.verifiedFrom) || date.isAfter(dataset.verifiedUntil)) {
            return TransportService(ServiceStatus.EXPIRED, dataset.source, date, emptyList())
        }
        val overrideActive = dataset.overrideFrom != null && dataset.overrideUntil != null &&
            date in dataset.overrideFrom..dataset.overrideUntil && date.dayOfWeek in WEEKDAYS
        val candidates = if (overrideActive) dataset.overrideDirections + dataset.directions else dataset.directions
        val direction = candidates.firstOrNull { it.id == directionId || "${it.from}-${it.to}" == directionId }
            ?: return TransportService(ServiceStatus.UNKNOWN, dataset.source, date, emptyList())

        if (date in dataset.excludedDates) {
            return TransportService(ServiceStatus.NO_SERVICE, dataset.source, date, emptyList())
        }
        val departures = when {
            overrideActive && date.dayOfWeek in WEEKDAYS -> direction.weekdays
            date in dataset.includedDates -> direction.weekdays
            date.dayOfWeek in WEEKDAYS -> direction.weekdays
            date.dayOfWeek == DayOfWeek.SATURDAY -> direction.saturday
            else -> emptyList()
        }
        return if (departures.isEmpty()) {
            TransportService(ServiceStatus.NO_SERVICE, dataset.source, date, emptyList())
        } else {
            TransportService(ServiceStatus.VERIFIED, dataset.source, date, departures, laterDepartures = departures)
        }
    }

    companion object {
        private val WEEKDAYS = DayOfWeek.MONDAY..DayOfWeek.FRIDAY

        fun fromContext(context: Context): AssetTransportRepository {
            val datasets = Institution.values().associateWith { institution ->
                val name = "institutions/${institution.name.lowercase()}_2026.json"
                context.assets.open(name).use { TransportDatasetParser.parse(it.readBytes().toString(Charsets.UTF_8)) }
            }
            return AssetTransportRepository(datasets)
        }
    }
}
