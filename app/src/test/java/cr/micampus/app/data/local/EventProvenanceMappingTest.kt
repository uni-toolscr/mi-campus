package cr.micampus.app.data.local

import cr.micampus.app.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EventProvenanceMappingTest {
    @Test fun draftRoundTripKeepsSourceDocument() {
        val draft = CalendarEventDraft(
            id = "draft", title = "Examen", category = EventCategory.EXAM, institution = Institution.UCR,
            date = LocalDate.of(2026, 8, 3), startTime = LocalTime.of(8, 0), endTime = LocalTime.of(9, 0),
            location = null, course = null, sourcePage = 2, evidence = Evidence(2, "evidencia"), sourceDocumentId = "document",
        )
        assertEquals("document", EventRepository.toDraft(draft.toEntity()).sourceDocumentId)
    }

    @Test fun confirmedRoundTripKeepsSourceDocument() {
        val event = CampusEvent(
            id = "event", title = "Clase", institution = Institution.UNA, kind = EventKind.CLASS,
            start = LocalDateTime.of(2026, 8, 3, 8, 0), end = LocalDateTime.of(2026, 8, 3, 9, 0),
            sourceDocumentId = "document",
        )
        assertEquals("document", EventRepository.toDomain(event.toEntity()).sourceDocumentId)
    }

    @Test fun confirmedRoundTripKeepsReminderPreference() {
        val event = CampusEvent(
            id = "event", title = "Clase", institution = Institution.UNA, kind = EventKind.CLASS,
            start = LocalDateTime.of(2026, 8, 3, 8, 0), end = LocalDateTime.of(2026, 8, 3, 9, 0),
            notifyThirtyMinutesBefore = true,
        )

        assertEquals(true, EventRepository.toDomain(event.toEntity()).notifyThirtyMinutesBefore)
    }
}
