package cr.micampus.app.feature.home

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class HomeUpcomingEventsTest {
    @Test fun classesAreRemovedBeforeTheLimitIsApplied() {
        val now = LocalDateTime.of(2026, 7, 17, 12, 0)
        val events = listOf(
            event("class-1", EventKind.CLASS, now.plusHours(1)),
            event("class-2", EventKind.CLASS, now.plusHours(2)),
        ) + (1..6).map { event("event-$it", EventKind.EXAM, now.plusHours((it + 2).toLong())) }

        assertEquals(listOf("event-1", "event-2", "event-3", "event-4", "event-5"), homeUpcomingEvents(events, now).map(CampusEvent::id))
    }

    private fun event(id: String, kind: EventKind, start: LocalDateTime) = CampusEvent(
        id = id,
        title = id,
        institution = Institution.UCR,
        kind = kind,
        start = start,
        end = start.plusHours(1),
    )
}
