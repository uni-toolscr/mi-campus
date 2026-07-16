package cr.micampus.app.data.institution

import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
}

private fun readAsset(name: String): String {
    val candidates = listOf(File("src/main/assets/institutions/$name"), File("app/src/main/assets/institutions/$name"))
    return candidates.firstOrNull(File::exists)?.readText() ?: error("asset missing: $name")
}
