package cr.micampus.app.feature.transport

import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NextDepartureTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val tuesday = monday.plusDays(1)
    private val times = listOf("05:35", "05:50", "06:00", "06:30", "07:00", "07:30", "21:05")
    private val days = listOf(verified(monday), verified(tuesday))

    private fun verified(date: LocalDate) = TransportService(ServiceStatus.VERIFIED, "UCR", date, times)

    @Test fun duringTheDayLeadsWithTodaysNextDeparture() {
        val next = nextDeparture(days, LocalDateTime.of(monday, LocalTime.of(5, 50)))!!
        assertEquals(monday, next.date)
        assertEquals(LocalTime.of(6, 0), next.time)
        assertEquals(2, next.index)
        assertEquals("PRÓXIMA SALIDA", nextDepartureOverline(next.date, monday))
        assertEquals("Quedan 5 salidas hoy", nextDepartureSupport(next, days.first(), monday, use12h = false))
    }

    @Test fun afterTheLastBusLeadsWithTomorrowsFirst() {
        val now = LocalDateTime.of(monday, LocalTime.of(21, 42))
        val next = nextDeparture(days, now)!!
        assertEquals(LocalDateTime.of(tuesday, LocalTime.of(5, 35)), next.dateTime)
        assertEquals("PRÓXIMA SALIDA · MAÑANA", nextDepartureOverline(next.date, monday))
        assertEquals("en 7 h 53 min", relativeDurationText(minutesUntil(now, next.dateTime)))
        assertEquals("Hoy ya no hay salidas · la última fue a las 21:05", nextDepartureSupport(next, days.first(), monday, use12h = false))
    }

    @Test fun dayWithoutServiceSkipsToTheNextServiceDay() {
        val sunday = LocalDate.of(2026, 11, 8)
        val closed = TransportService(ServiceStatus.NO_SERVICE, "UCR", sunday, emptyList())
        val nextMonday = verified(sunday.plusDays(1))
        val next = nextDeparture(listOf(closed, nextMonday), LocalDateTime.of(sunday, LocalTime.of(12, 0)))!!
        assertEquals(sunday.plusDays(1), next.date)
        assertEquals("Hoy no hay servicio", nextDepartureSupport(next, closed, sunday, use12h = false))
    }

    @Test fun departureAtTheCurrentMinuteHasAlreadyLeft() {
        val next = nextDeparture(days, LocalDateTime.of(monday, LocalTime.of(21, 5)))!!
        assertEquals(tuesday, next.date)
        val last = nextDeparture(days, LocalDateTime.of(monday, LocalTime.of(21, 4)))!!
        assertEquals("Última salida de hoy", nextDepartureSupport(last, days.first(), monday, use12h = false))
    }

    @Test fun staleDaysAreIgnoredAfterMidnight() {
        // The screen stayed open past midnight: Monday's list is stale, Tuesday is now today.
        val next = nextDeparture(days, LocalDateTime.of(tuesday, LocalTime.of(0, 10)))!!
        assertEquals(tuesday, next.date)
        assertEquals(0, next.index)
    }

    @Test fun nothingLeftWhenScheduleIsExpiredOrExhausted() {
        val expired = TransportService(ServiceStatus.EXPIRED, "UCR", monday, emptyList())
        assertNull(nextDeparture(listOf(expired), LocalDateTime.of(monday, LocalTime.of(8, 0))))
        assertNull(nextDeparture(listOf(verified(monday)), LocalDateTime.of(monday, LocalTime.of(22, 0))))
    }

    @Test fun previewShowsFourFollowingAndOffersTheRest() {
        val first = nextDeparture(days, LocalDateTime.of(monday, LocalTime.of(5, 0)))!!
        assertEquals(listOf("05:50", "06:00", "06:30", "07:00").map(LocalTime::parse), followingDepartures(first, 4))
        assertTrue(hasMoreDepartures(first, 4))
        val late = nextDeparture(days, LocalDateTime.of(monday, LocalTime.of(7, 0)))!!
        assertEquals(listOf(LocalTime.of(21, 5)), followingDepartures(late, 4))
        // Earlier departures of the day are only reachable through the full list.
        assertTrue(hasMoreDepartures(late, 4))
        val short = NextDeparture(monday, listOf("06:00", "07:00").map(LocalTime::parse), 0)
        assertFalse(hasMoreDepartures(short, 4))
    }

    @Test fun dayLabels() {
        assertEquals("Mañana, martes 3", transportDayHeading(tuesday, monday))
        assertEquals("Hoy, lunes 2", transportDayHeading(monday, monday))
        assertEquals("Miércoles 4", transportDayHeading(monday.plusDays(2), monday))
        assertEquals("Ver las 19 salidas de mañana", allDeparturesLabel(19, tuesday, monday))
        assertEquals("Ver las 19 salidas del miércoles 4", allDeparturesLabel(19, monday.plusDays(2), monday))
    }

    @Test fun datasetSummaryIsOneLine() {
        val dataset = TransportDataset(
            version = 1,
            institution = Institution.UCR,
            source = "UCR-Transportes-2026",
            sourceUrl = null,
            lastVerified = LocalDate.of(2026, 7, 16),
            verifiedFrom = LocalDate.of(2026, 1, 1),
            verifiedUntil = LocalDate.of(2026, 12, 31),
            directions = emptyList(),
        )
        assertEquals("Horario verificado · UCR 2026 · vigente hasta 31 dic", datasetSummary(dataset, monday))
        assertEquals("Horario vencido · UCR 2026 · venció el 31 dic", datasetSummary(dataset, LocalDate.of(2027, 1, 1)))
    }
}
