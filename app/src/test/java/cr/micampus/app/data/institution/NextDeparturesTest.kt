package cr.micampus.app.data.institution

import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class NextDeparturesTest {
    private val ucr = TransportDatasetParser.parse(readAsset("ucr_2026.json"))
    private val una = TransportDatasetParser.parse(readAsset("una_2026.json"))
    private val repository = AssetTransportRepository(mapOf(Institution.UCR to ucr, Institution.UNA to una))

    @Test fun mergesInstitutionsSortedByTime() {
        val now = LocalDateTime.of(2026, 7, 10, 5, 0)
        val departures = repository.upcomingDepartures(listOf(Institution.UCR, Institution.UNA), now)
        assertTrue(departures.isNotEmpty())
        assertEquals(departures.sortedBy { it.departure }, departures)
        assertTrue(departures.all { it.departure.toLocalTime().isAfter(now.toLocalTime()) })
    }

    @Test fun filtersToSingleInstitution() {
        val now = LocalDateTime.of(2026, 7, 10, 5, 0)
        val departures = repository.upcomingDepartures(listOf(Institution.UNA), now)
        assertTrue(departures.all { it.institution == Institution.UNA })
    }

    @Test fun emptyWhenNoServiceRemainsToday() {
        val now = LocalDateTime.of(2026, 7, 10, 23, 30)
        assertTrue(repository.upcomingDepartures(listOf(Institution.UCR, Institution.UNA), now).isEmpty())
    }

    @Test fun nullDirectionFilterIsUnchanged() {
        val now = LocalDateTime.of(2026, 7, 10, 5, 0)
        val all = repository.upcomingDepartures(listOf(Institution.UNA), now)
        val explicitNull = repository.upcomingDepartures(listOf(Institution.UNA), now, directionIds = null)
        assertEquals(all, explicitNull)
    }

    @Test fun directionFilterKeepsOnlySelectedDirection() {
        val now = LocalDateTime.of(2026, 7, 10, 5, 0)
        val all = repository.upcomingDepartures(listOf(Institution.UNA), now)
        assertTrue("test needs at least one UNA departure", all.isNotEmpty())
        val chosen = all.first().directionId
        val key = directionKey(Institution.UNA, chosen)
        val filtered = repository.upcomingDepartures(listOf(Institution.UNA), now, directionIds = setOf(key))
        assertTrue(filtered.isNotEmpty())
        assertTrue(filtered.all { it.directionId == chosen })
    }

    @Test fun emptyDirectionFilterMeansAllDirections() {
        val now = LocalDateTime.of(2026, 7, 10, 5, 0)
        val all = repository.upcomingDepartures(listOf(Institution.UNA), now)
        val empty = repository.upcomingDepartures(listOf(Institution.UNA), now, directionIds = emptySet())
        assertEquals(all, empty)
    }

    @Test fun departureGraceRetainsDepartureOnlyUntilOneMinuteAfterItLeaves() {
        val departure = LocalDateTime.of(2026, 7, 10, 14, 15)
        val defaultDepartures = repository.upcomingDepartures(listOf(Institution.UNA), departure)
        val widgetDepartures = repository.upcomingDepartures(
            institutions = listOf(Institution.UNA),
            now = departure,
            departureGrace = Duration.ofMinutes(1),
        )
        val afterGrace = repository.upcomingDepartures(
            institutions = listOf(Institution.UNA),
            now = departure.plusMinutes(1),
            departureGrace = Duration.ofMinutes(1),
        )

        assertTrue(defaultDepartures.none { it.departure == departure })
        assertTrue(widgetDepartures.any { it.departure == departure })
        assertTrue(afterGrace.none { it.departure == departure })
    }

    @Test fun serviceDaysStartWithTodayAndSkipToVerifiedDays() {
        // Saturday 2026-11-07, Sunday has no service, so the next verified days are Monday and Tuesday.
        val saturday = LocalDate.of(2026, 11, 7)
        val days = repository.serviceDays(Institution.UCR, "Heredia-UCR", saturday)
        assertEquals(listOf(saturday, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10)), days.map { it.date })
        assertTrue(days.all { it.status == ServiceStatus.VERIFIED })
        val sunday = repository.serviceDays(Institution.UCR, "Heredia-UCR", saturday.plusDays(1))
        assertEquals(ServiceStatus.NO_SERVICE, sunday.first().status)
        assertEquals(LocalDate.of(2026, 11, 9), sunday[1].date)
    }

    @Test fun serviceDaysEndWithTheVerifiedRange() {
        val days = repository.serviceDays(Institution.UCR, "Heredia-UCR", LocalDate.of(2026, 12, 31))
        assertEquals(listOf(LocalDate.of(2026, 12, 31)), days.map { it.date })
        assertEquals(ServiceStatus.EXPIRED, repository.serviceDays(Institution.UCR, "Heredia-UCR", LocalDate.of(2027, 1, 1)).single().status)
    }
}

private fun readAsset(name: String): String {
    val candidates = listOf(File("src/main/assets/institutions/$name"), File("app/src/main/assets/institutions/$name"))
    return candidates.firstOrNull(File::exists)?.readText() ?: error("asset missing: $name")
}
