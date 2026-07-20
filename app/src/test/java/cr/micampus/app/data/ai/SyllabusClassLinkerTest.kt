package cr.micampus.app.data.ai

import cr.micampus.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class SyllabusClassLinkerTest {
    private val monThu = CourseGroup("01", setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalTime.of(8, 0), LocalTime.of(9, 40))
    private val tuesday = CourseGroup("02", setOf(DayOfWeek.TUESDAY), LocalTime.of(8, 0), LocalTime.of(9, 40))
    private val syllabus = ExtractedSyllabus(Course("EIF-203", "Programación"), Institution.UCR, listOf(monThu, tuesday),
        listOf(SyllabusWeek(1, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 19), "Introducción")))
    private fun event(id: String, date: LocalDate, code: String? = "EIF203", title: String = "Programación EIF-203", institution: Institution = Institution.UCR) =
        CampusEvent(id, title, institution, EventKind.CLASS, LocalDateTime.of(date, LocalTime.of(8, 0)), LocalDateTime.of(date, LocalTime.of(9, 40)), notes = "Aula 1", courseCode = code, source = "manual", allDay = true, externalId = "remote")

    @Test fun uniqueGroupMapsInclusiveRangeAndPreservesNotesIdAndMetadataDraftFields() {
        val result = SyllabusClassLinker.link(syllabus, listOf(event("m", LocalDate.of(2026, 2, 16)), event("t", LocalDate.of(2026, 2, 19))), now = LocalDate.of(2026, 1, 1))
        assertEquals(monThu, result.uniqueGroup)
        assertEquals(listOf("m", "t"), result.drafts.map { it.id })
        assertTrue(result.drafts.all { it.description == "Aula 1\nTema: Introducción" })
        assertTrue(result.drafts.all { it.course?.code == "EIF203" })
    }

    @Test fun titleFallbackAndCodePrecedenceAvoidWrongCodeAndAmbiguity() {
        val titleOnly = event("title", LocalDate.of(2026, 2, 16), code = null)
        assertEquals(monThu, SyllabusClassLinker.link(syllabus, listOf(titleOnly)).uniqueGroup)
        val wrongCode = event("wrong", LocalDate.of(2026, 2, 16), code = "MAT101")
        assertNull(SyllabusClassLinker.link(syllabus, listOf(wrongCode)).uniqueGroup)
    }

    @Test fun titleFallbackUsesWordBoundariesAndRejectsRelatedCourseNames() {
        val discrete = syllabus.copy(course = Course(null, "Estructuras Discretas"))
        val extendedTitle = event("discrete", LocalDate.of(2026, 2, 16), code = null, title = "Estructuras Discretas para Informática")
        assertEquals(monThu, SyllabusClassLinker.link(discrete, listOf(extendedTitle)).uniqueGroup)

        val calculus = syllabus.copy(course = Course(null, "Cálculo I"))
        val otherCourse = event("calculus", LocalDate.of(2026, 2, 16), code = null, title = "Cálculo II")
        assertNull(SyllabusClassLinker.link(calculus, listOf(otherCourse)).uniqueGroup)
    }

    @Test fun courseCodeInTitleUsesAlphanumericBoundaries() {
        val exactCode = event("exact", LocalDate.of(2026, 2, 16), code = null, title = "Programación EIF-203")
        assertEquals(monThu, SyllabusClassLinker.link(syllabus, listOf(exactCode)).uniqueGroup)

        val similarCode = syllabus.copy(course = Course("MAT101", "Matemática"))
        val wrongTitle = event("wrong", LocalDate.of(2026, 2, 16), code = null, title = "Matemática MAT1010")
        assertNull(SyllabusClassLinker.link(similarCode, listOf(wrongTitle)).uniqueGroup)
    }

    @Test fun occurrenceOutsideAllWeekRangesDoesNotResolveAGroup() {
        assertNull(SyllabusClassLinker.link(syllabus, listOf(event("old", LocalDate.of(2025, 8, 18)))).uniqueGroup)
    }

    @Test fun twoTrulyMatchingExtractedGroupsAreAmbiguous() {
        val ambiguous = syllabus.copy(groups = listOf(monThu, tuesday))
        val events = listOf(event("mon", LocalDate.of(2026, 2, 16)), event("tue", LocalDate.of(2026, 2, 17)))
        assertNull(SyllabusClassLinker.link(ambiguous, events).uniqueGroup)
    }

    @Test fun institutionOrTimeMismatchDoesNotLinkAndTopicIsIdempotent() {
        assertTrue(SyllabusClassLinker.link(syllabus, listOf(event("una", LocalDate.of(2026, 2, 16), institution = Institution.UNA))).drafts.isEmpty())
        assertTrue(SyllabusClassLinker.link(syllabus, listOf(event("late", LocalDate.of(2026, 2, 16)).copy(start = LocalDateTime.of(2026, 2, 16, 10, 0)))).drafts.isEmpty())
        val tagged = event("tag", LocalDate.of(2026, 2, 16)).copy(notes = "Tema: Introducción")
        assertEquals("Tema: Introducción", SyllabusClassLinker.link(syllabus, listOf(tagged)).drafts.single().description)
    }

    @Test fun announcementOnlyWeekDoesNotCreateTopicDrafts() {
        for (announcement in listOf("Feriado", "Examen parcial", "Quiz 1")) {
            val announcementSyllabus = syllabus.copy(weeks = listOf(syllabus.weeks.single().copy(topic = announcement)))
            assertTrue(announcement, SyllabusClassLinker.link(announcementSyllabus, listOf(event("class", LocalDate.of(2026, 2, 16)))).drafts.isEmpty())
        }
    }
}
