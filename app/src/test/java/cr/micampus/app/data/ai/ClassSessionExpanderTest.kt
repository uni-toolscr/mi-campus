package cr.micampus.app.data.ai

import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.CourseGroup
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.ExtractedSyllabus
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.SyllabusWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ClassSessionExpanderTest {
    // Fixture values taken from "Carta al Estudiante Fundamentos I-2025" (EIF200, UNA).
    private val groupLJ = CourseGroup("01", setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalTime.of(8, 0), LocalTime.of(9, 40), "Karol Leitón Arrieta")
    private val syllabus = ExtractedSyllabus(
        course = Course("EIF200", "Fundamentos de Informática"),
        institution = Institution.UNA,
        groups = listOf(groupLJ),
        weeks = listOf(
            SyllabusWeek(1, LocalDate.of(2025, 2, 17), LocalDate.of(2025, 2, 23), "Presentación del curso y Elementos básicos de Computación"),
            SyllabusWeek(8, LocalDate.of(2025, 4, 7), LocalDate.of(2025, 4, 13), "Elementos básicos de programación orientada a objetos (POO)"),
            SyllabusWeek(9, LocalDate.of(2025, 4, 14), LocalDate.of(2025, 4, 20), "Semana Santa"),
            SyllabusWeek(11, LocalDate.of(2025, 4, 28), LocalDate.of(2025, 5, 4), "Elementos básicos de programación orientada a objetos (POO)"),
        ),
        holidays = listOf(LocalDate.of(2025, 4, 11), LocalDate.of(2025, 5, 1)),
    )
    private val today = LocalDate.of(2025, 2, 1)

    @Test fun expandsWeeksIntoDatedClassSessionsWithTopic() {
        val drafts = ClassSessionExpander.expand(syllabus, groupLJ, today)
        val week1 = drafts.filter { it.date?.month == java.time.Month.FEBRUARY }
        assertEquals(listOf(LocalDate.of(2025, 2, 17), LocalDate.of(2025, 2, 20)), week1.map { it.date })
        assertTrue(week1.all { it.title == "Fundamentos de Informática · EIF200" })
        assertTrue(week1.all { it.description == "Presentación del curso y Elementos básicos de Computación" })
        assertTrue(week1.all { it.category == EventCategory.CLASS })
        assertTrue(week1.all { it.startTime == LocalTime.of(8, 0) && it.endTime == LocalTime.of(9, 40) })
        assertTrue(week1.all { it.institution == Institution.UNA })
        assertTrue(week1.all { it.course?.code == "EIF200" })
        assertTrue(week1.all { it.issues.isEmpty() })
    }

    @Test fun semanaSantaWeekEmitsNothing() {
        val drafts = ClassSessionExpander.expand(syllabus, groupLJ, today)
        assertTrue(drafts.none { it.date != null && it.date!! >= LocalDate.of(2025, 4, 14) && it.date!! <= LocalDate.of(2025, 4, 20) })
    }

    @Test fun holidayDatesAreSkipped() {
        // 2025-05-01 is a Thursday (Día del Trabajo) inside week 11 of an L-J group.
        val drafts = ClassSessionExpander.expand(syllabus, groupLJ, today)
        assertTrue(drafts.none { it.date == LocalDate.of(2025, 5, 1) })
        assertTrue(drafts.any { it.date == LocalDate.of(2025, 4, 28) })
    }

    @Test fun pastSessionsAreFlagged() {
        val drafts = ClassSessionExpander.expand(syllabus, groupLJ, LocalDate.of(2025, 4, 1))
        assertTrue(drafts.first { it.date == LocalDate.of(2025, 2, 17) }.issues.contains(ImportIssue.PAST))
        assertTrue(drafts.first { it.date == LocalDate.of(2025, 4, 7) }.issues.isEmpty())
    }

    @Test fun idsAreStableAcrossRuns() {
        val first = ClassSessionExpander.expand(syllabus, groupLJ, today).map { it.id }
        val second = ClassSessionExpander.expand(syllabus, groupLJ, today).map { it.id }
        assertEquals(first, second)
    }

    @Test fun mixedTopicCellStripsEmbeddedFeriadoFromTitleButKeepsWeek() {
        // Reference doc week 8: topic cell holds a real topic plus an inline single-day feriado line.
        val mixed = syllabus.copy(
            weeks = listOf(
                SyllabusWeek(8, LocalDate.of(2025, 4, 7), LocalDate.of(2025, 4, 13),
                    "Elementos básicos de programación orientada a objetos (POO) Viernes 11 de abril: Feriado Celebración de la Batalla de Rivas."),
            ),
            holidays = listOf(LocalDate.of(2025, 4, 11)),
        )
        val drafts = ClassSessionExpander.expand(mixed, groupLJ, today)
        assertTrue(drafts.isNotEmpty())
        assertTrue(drafts.all { it.title == "Fundamentos de Informática · EIF200" })
        assertTrue(drafts.all { it.description == "Elementos básicos de programación orientada a objetos (POO)" })
        assertTrue(drafts.none { it.date == LocalDate.of(2025, 4, 11) })
    }

    @Test fun examAnnouncementIsStrippedFromClassTitle() {
        // Reference doc week 7: topic ends with a "Prueba de ejecución" announcement (a separate event).
        val week7 = syllabus.copy(
            weeks = listOf(
                SyllabusWeek(7, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 6),
                    "Recapitulación y Práctica de Estructuras Condicionales, Iterativas y Funciones. Prueba de ejecución 1: Domingo 06 de abril, 09:00 am."),
            ),
            holidays = emptyList(),
        )
        val drafts = ClassSessionExpander.expand(week7, groupLJ, today)
        assertTrue(drafts.isNotEmpty())
        assertTrue(drafts.all { it.title == "Fundamentos de Informática · EIF200" })
        assertTrue(drafts.all { it.description == "Recapitulación y Práctica de Estructuras Condicionales, Iterativas y Funciones" })
    }

    @Test fun semanaSantaVariantsAreRecognizedAsBreaks() {
        for (phrase in listOf("Semana Santa", "Semana Santa (no hay lecciones)", "Semana Santa no hay clases", "Receso", "Vacaciones")) {
            val week = syllabus.copy(weeks = listOf(SyllabusWeek(9, LocalDate.of(2025, 4, 14), LocalDate.of(2025, 4, 20), phrase)), holidays = emptyList())
            assertTrue("Expected no classes for '$phrase'", ClassSessionExpander.expand(week, groupLJ, today).isEmpty())
        }
    }

    @Test fun noCourseFallsBackToTopicWithoutDescription() {
        val noCourse = syllabus.copy(course = null, weeks = listOf(syllabus.weeks.first()))
        val draft = ClassSessionExpander.expand(noCourse, groupLJ, today).first()
        assertEquals("Presentación del curso y Elementos básicos de Computación", draft.title)
        assertEquals(null, draft.description)
    }

    @Test fun missingGroupTimeYieldsMissingTimeIssue() {
        val group = groupLJ.copy(startTime = null, endTime = null)
        val drafts = ClassSessionExpander.expand(syllabus, group, today)
        assertTrue(drafts.isNotEmpty())
        assertTrue(drafts.all { ImportIssue.MISSING_TIME in it.issues })
    }
}
