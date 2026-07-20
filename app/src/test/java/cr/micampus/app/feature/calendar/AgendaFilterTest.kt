package cr.micampus.app.feature.calendar

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AgendaFilterTest {
    private val start = LocalDateTime.of(2026, 7, 20, 9, 0)

    @Test fun defaultFilterHidesClassesOnly() {
        val events = listOf(event("class", EventKind.CLASS), event("exam", EventKind.EXAM))

        assertEquals(listOf("exam"), filterAgendaEvents(events, CalendarFilters()).map(CampusEvent::id))
    }

    @Test fun exclusionsAndSearchAreCombined() {
        val events = listOf(
            event("ucr-match", EventKind.EXAM, title = "Álgebra", institution = Institution.UCR),
            event("una-match", EventKind.EXAM, title = "Álgebra", institution = Institution.UNA),
            event("ucr-quiz", EventKind.QUIZ, title = "Álgebra", institution = Institution.UCR),
            event("ucr-other", EventKind.EXAM, title = "Cálculo", institution = Institution.UCR),
        )
        val filters = CalendarFilters(
            excludedInstitutions = setOf(Institution.UNA),
            excludedKinds = EventKind.values().toSet() - EventKind.EXAM,
            courseQuery = "álgebra",
        )

        assertEquals(listOf("ucr-match"), filterAgendaEvents(events, filters).map(CampusEvent::id))
    }

    @Test fun excludingEveryKindProducesAnEmptyAgenda() {
        assertTrue(filterAgendaEvents(listOf(event("exam", EventKind.EXAM)), CalendarFilters(excludedKinds = EventKind.values().toSet())).isEmpty())
    }

    private fun event(id: String, kind: EventKind, title: String = id, institution: Institution = Institution.UCR) = CampusEvent(
        id = id,
        title = title,
        institution = institution,
        kind = kind,
        start = start,
        end = start.plusHours(1),
    )
}
