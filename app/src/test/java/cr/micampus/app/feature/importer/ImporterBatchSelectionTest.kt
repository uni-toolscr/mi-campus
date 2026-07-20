package cr.micampus.app.feature.importer

import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImporterBatchSelectionTest {
    @Test fun selectedWarningsAreConfirmableWhileInvalidDraftsRemainExcluded() {
        val warned = draft("warned", title = "Examen", issues = setOf(ImportIssue.DUPLICATE))
        val invalid = draft("invalid", title = null, issues = setOf(ImportIssue.MISSING_DATE))
        val unselected = draft("other", title = "Quiz")

        val confirmed = selectedDraftEvents(listOf(warned, invalid, unselected), setOf("warned", "invalid"))

        assertEquals(listOf("warned"), confirmed.map { it.id })
    }

    private fun draft(id: String, title: String?, issues: Set<ImportIssue> = emptySet()) = CalendarEventDraft(
        id = id,
        title = title,
        category = EventCategory.EXAM,
        institution = Institution.UCR,
        date = LocalDate.of(2026, 8, 1),
        startTime = LocalTime.of(9, 0),
        endTime = LocalTime.of(10, 0),
        location = null,
        course = null,
        sourcePage = null,
        evidence = null,
        issues = issues,
    )
}
