package cr.micampus.app.data.institution

import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

class AssetTransportRepositoryTest {
    private val ucr = TransportDatasetParser.parse(readAsset("ucr_2026.json"))
    private val una = TransportDatasetParser.parse(readAsset("una_2026.json"))
    private val repository = AssetTransportRepository(mapOf(Institution.UCR to ucr, Institution.UNA to una))
    private val ucrOut = ucr.directions.first().id
    private val unaOut = una.directions.first().id

    @Test fun july13AndAugust7UseInclusiveOverride() {
        assertEquals("06:00", repository.service(Institution.UCR, ucrOut, LocalDate.of(2026, 7, 13)).departures.first())
        assertEquals("06:00", repository.service(Institution.UCR, ucrOut, LocalDate.of(2026, 8, 7)).departures.first())
    }

    @Test fun weekdaysOutsideOverrideUseRegularSchedule() {
        assertEquals("05:35", repository.service(Institution.UCR, ucrOut, LocalDate.of(2026, 7, 10)).departures.first())
        assertEquals("05:35", repository.service(Institution.UCR, ucrOut, LocalDate.of(2026, 8, 10)).departures.first())
    }

    @Test fun saturdayInsideOverrideRangeStillUsesRegularSaturdayCalendar() {
        val service = repository.service(Institution.UCR, ucrOut, LocalDate.of(2026, 7, 18))
        assertEquals(ServiceStatus.VERIFIED, service.status)
        assertEquals(listOf("07:00"), service.departures)
    }

    @Test fun unaWeekendPointsToNextWeekday() {
        val service = repository.service(Institution.UNA, unaOut, LocalDate.of(2026, 7, 18))
        assertEquals(ServiceStatus.NO_SERVICE, service.status)
        assertEquals(LocalDate.of(2026, 7, 20), service.nextValidDate)
    }

    @Test fun unaExpiryNeverShowsServiceOrCountdown() {
        val service = repository.service(Institution.UNA, unaOut, LocalDate.of(2027, 1, 1))
        assertEquals(ServiceStatus.EXPIRED, service.status)
        assertEquals(emptyList<String>(), service.departures)
        assertNull(service.nextValidDate)
    }

    @Test fun explicitInclusionsAndExclusionsControlServiceBeforeNextDateSearch() {
        val includedSunday = LocalDate.of(2026, 7, 19)
        val excludedMonday = LocalDate.of(2026, 7, 20)
        val customUna = una.copy(
            includedDates = setOf(includedSunday),
            excludedDates = setOf(excludedMonday),
        )
        val custom = AssetTransportRepository(mapOf(Institution.UNA to customUna))

        assertEquals(ServiceStatus.VERIFIED, custom.service(Institution.UNA, unaOut, includedSunday).status)
        val excluded = custom.service(Institution.UNA, unaOut, excludedMonday)
        assertEquals(ServiceStatus.NO_SERVICE, excluded.status)
        assertEquals(LocalDate.of(2026, 7, 21), excluded.nextValidDate)
    }

    private fun readAsset(name: String): String {
        val candidates = listOf(File("src/main/assets/institutions/$name"), File("app/src/main/assets/institutions/$name"))
        return candidates.firstOrNull(File::exists)?.readText() ?: error("asset missing: $name")
    }
}
