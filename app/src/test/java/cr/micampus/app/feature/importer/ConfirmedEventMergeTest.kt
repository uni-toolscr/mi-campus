package cr.micampus.app.feature.importer

import cr.micampus.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class ConfirmedEventMergeTest {
    @Test fun mergeKeepsExistingOwnershipMetadataAndId() {
        val old = CampusEvent("same", "Horario", Institution.UCR, EventKind.CLASS, LocalDateTime.of(2026, 2, 1, 8, 0), LocalDateTime.of(2026, 2, 1, 9, 0), source = "manual", allDay = true, courseCode = "EIF203", externalId = "x", externalUrl = "url", externalModifiedEpoch = 4, sourceDocumentId = "old")
        val edited = old.copy(title = "Horario enriquecido", notes = "Tema: 1", source = "importado", allDay = false, courseCode = null, sourceDocumentId = "new")
        val merged = mergeConfirmedEvent(old, edited)
        assertEquals("same", merged.id); assertEquals("manual", merged.source); assertTrue(merged.allDay)
        assertEquals("x", merged.externalId); assertEquals("url", merged.externalUrl); assertEquals(4L, merged.externalModifiedEpoch)
        assertEquals("EIF203", merged.courseCode); assertEquals("new", merged.sourceDocumentId); assertEquals("Tema: 1", merged.notes)
    }
}
