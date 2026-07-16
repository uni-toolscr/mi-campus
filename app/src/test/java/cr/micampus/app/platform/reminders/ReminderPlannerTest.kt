package cr.micampus.app.platform.reminders

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPlannerTest {
    private val zone = ZoneId.of("America/Costa_Rica")
    private val now = Instant.parse("2026-07-16T12:00:00Z")
    private val clock = Clock.fixed(now, zone)

    @Test fun timedDefaultsAre24HoursAnd1Hour() {
        assertEquals(listOf(1_440L, 60L), ReminderPlanner.defaultOffsets(allDay = false))
    }

    @Test fun allDayDefaultIs24Hours() {
        assertEquals(listOf(1_440L), ReminderPlanner.defaultOffsets(allDay = true))
    }

    @Test fun pastTriggersAreNotScheduled() {
        val event = event(LocalDateTime.of(2026, 7, 16, 6, 0))
        assertTrue(ReminderPlanner.plan(event, listOf(60), clock, zone).isEmpty())
    }

    @Test fun eventAndOffsetProduceIndependentRequests() {
        val event = event(LocalDateTime.of(2026, 7, 18, 9, 0))
        val requests = ReminderPlanner.plan(event, listOf(1_440, 60), clock, zone)
        assertEquals(2, requests.size)
        assertEquals(2, requests.map { it.uniqueName }.distinct().size)
    }

    private fun event(start: LocalDateTime) = CampusEvent(
        id = "e1",
        title = "Examen",
        institution = Institution.UCR,
        kind = EventKind.EXAM,
        start = start,
        end = start.plusHours(2),
    )
}
