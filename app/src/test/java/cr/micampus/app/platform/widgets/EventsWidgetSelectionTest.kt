package cr.micampus.app.platform.widgets

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EventsWidgetSelectionTest {
    private val start = LocalDateTime.of(2026, 7, 20, 9, 0)

    private fun event(id: String, kind: EventKind, institution: Institution) = CampusEvent(
        id = id,
        title = id,
        institution = institution,
        kind = kind,
        start = start,
        end = start.plusHours(1),
    )

    private val clase = event("clase", EventKind.CLASS, Institution.UCR)
    private val examen = event("examen", EventKind.EXAM, Institution.UCR)
    private val tareaUna = event("tarea", EventKind.TAREA, Institution.UNA)

    @Test
    fun `drops excluded kinds`() {
        val result = agendaVisibleEvents(listOf(clase, examen, tareaUna), setOf(EventKind.CLASS), emptySet())
        assertEquals(listOf(examen, tareaUna), result)
    }

    @Test
    fun `drops excluded institutions`() {
        val result = agendaVisibleEvents(listOf(clase, examen, tareaUna), emptySet(), setOf(Institution.UNA))
        assertEquals(listOf(clase, examen), result)
    }

    @Test
    fun `keeps everything when nothing is excluded`() {
        val all = listOf(clase, examen, tareaUna)
        assertEquals(all, agendaVisibleEvents(all, emptySet(), emptySet()))
    }
}
