package cr.micampus.app.platform.calendar

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.ExportRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class ExportPlannerTest {
    private val event = CampusEvent(
        id = "event-1",
        title = "Examen final",
        institution = Institution.UNA,
        kind = EventKind.EXAM,
        start = LocalDateTime.of(2026, 8, 3, 8, 0),
        end = LocalDateTime.of(2026, 8, 3, 10, 0),
    )

    @Test fun newEventIsInserted() {
        assertEquals(ExportAction.INSERT, ExportPlanner.plan(event, 4, null).action)
    }

    @Test fun unchangedEventIsSkipped() {
        val hash = ExportPlanner.contentHash(event, 4)
        val previous = ExportRecordEntity(event.id, "4", 99, 0, hash)
        assertEquals(ExportAction.UNCHANGED, ExportPlanner.plan(event, 4, previous).action)
    }

    @Test fun changedEventRequiresConfirmation() {
        val previous = ExportRecordEntity(event.id, "4", 99, 0, "old")
        assertEquals(ExportAction.UPDATE_REQUIRES_CONFIRMATION, ExportPlanner.plan(event.copy(title = "Nuevo"), 4, previous).action)
    }

    @Test fun contentHashCoversMutableFields() {
        assertNotEquals(ExportPlanner.contentHash(event, 4), ExportPlanner.contentHash(event.copy(location = "Aula 1"), 4))
    }
}
