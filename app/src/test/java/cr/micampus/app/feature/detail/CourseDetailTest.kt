package cr.micampus.app.feature.detail

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class CourseDetailTest {
    // Monday 2 November 2026, 21:42.
    private val now = LocalDateTime.of(2026, 11, 2, 21, 42)

    private val calculo = (0L..3L).flatMap { week ->
        listOf(
            classAt("mon-$week", now.withHour(10).withMinute(0).plusWeeks(week)),
            classAt("wed-$week", now.withHour(10).withMinute(0).plusDays(2).plusWeeks(week)),
        )
    }
    private val parcial = CampusEvent("parcial", "Parcial 2 · Cálculo II", Institution.UCR, EventKind.EXAM, now.plusDays(7).withHour(13).withMinute(0), now.plusDays(7).withHour(15).withMinute(0), courseCode = "MA-1002")
    private val events = calculo + parcial

    @Test fun courseDetailListsBlocksMondayFirstAndPendingEvaluations() {
        val course = courseDetail("Cálculo II", events, now)!!
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), course.slots.map { it.day })
        assertEquals("MA-1002", course.code)
        assertEquals(listOf("parcial"), course.evaluations.map { it.id })
        assertEquals("wed-0", course.nextSession?.id)
        assertEquals(emptyList<CampusEvent>(), courseDetail("Cálculo II", events.map { if (it.id == "parcial") it.copy(completed = true) else it }, now)!!.evaluations)
        assertNull(courseDetail("Física", events, now))
    }

    @Test fun classesBeforeAnExamAreTheSessionsStillAhead() {
        assertEquals(listOf("wed-0", "mon-1"), classesBefore(parcial, events, now).map { it.id })
    }

    @Test fun labels() {
        assertEquals("Examen · en 7 días", eventStatusLabel(parcial, now))
        assertEquals("Examen · pasado", eventStatusLabel(parcial, now.plusDays(8)))
        assertEquals("Tarea · hecha", eventStatusLabel(parcial.copy(kind = EventKind.TAREA, completed = true), now))
        assertEquals("7 días antes", automaticReminderLabel(EventKind.EXAM))
        assertNull(automaticReminderLabel(EventKind.CLASS))
        assertEquals("MA", courseMonogram("Cálculo II", "MA-1002"))
        assertEquals("FG", courseMonogram("Física General", null))
    }

    private fun classAt(id: String, start: LocalDateTime) =
        CampusEvent(id, "Cálculo II", Institution.UCR, EventKind.CLASS, start, start.plusMinutes(110), location = "Aula 204", courseCode = "MA-1002")
}
