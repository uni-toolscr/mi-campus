package cr.micampus.app.data.ai

import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.CourseGroup
import cr.micampus.app.core.model.ImportIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ScheduleTimeAutofillTest {
    private val monday = LocalDate.of(2026, 1, 5)

    @Test fun uniqueWeekdayAndCourseMatchFillsTime() {
        val draft = missingTime(course = Course("MAT-1", "Matemática")).copy(issues = setOf(ImportIssue.MISSING_TIME, ImportIssue.INVALID_RANGE))
        val schedule = listOf(session(monday, LocalTime.of(8, 0), LocalTime.of(9, 0), Course("mat-1", "Matemática")))

        val result = ScheduleTimeAutofill.fill(listOf(draft), schedule).single()

        assertEquals(LocalTime.of(8, 0), result.startTime)
        assertEquals(LocalTime.of(9, 0), result.endTime)
        assertFalse(ImportIssue.MISSING_TIME in result.issues)
        assertFalse(ImportIssue.INVALID_RANGE in result.issues)
        assertTrue(ImportIssue.INFERRED_TIME in result.issues)
    }

    @Test fun conflictingSlotsLeaveDraftUnchanged() {
        val draft = missingTime(course = Course("MAT-1", "Matemática"))
        val schedule = listOf(
            session(monday, LocalTime.of(8, 0), LocalTime.of(9, 0), Course("MAT-1", null)),
            session(monday, LocalTime.of(10, 0), LocalTime.of(11, 0), Course("MAT-1", null)),
        )

        assertEquals(draft, ScheduleTimeAutofill.fill(listOf(draft), schedule).single())
    }

    @Test fun noCourseMatchLeavesDraftUnchanged() {
        val draft = missingTime(course = Course("MAT-1", "Matemática"))
        val schedule = listOf(session(monday, LocalTime.of(8, 0), LocalTime.of(9, 0), Course("FIS-1", "Física")))

        assertEquals(draft, ScheduleTimeAutofill.fill(listOf(draft), schedule).single())
    }

    @Test fun conflictingCourseCodesRejectSimilarNames() {
        val draft = missingTime(course = Course("MAT-101", "Matemática general"))
        val schedule = listOf(session(monday, LocalTime.of(8, 0), LocalTime.of(9, 0), Course("FIS-101", "Matemática")))

        assertEquals(draft, ScheduleTimeAutofill.fill(listOf(draft), schedule).single())
    }

    @Test fun weekdayMismatchLeavesDraftUnchanged() {
        val draft = missingTime(course = Course("MAT-1", "Matemática"))
        val tuesday = monday.plusDays(1)
        val schedule = listOf(session(tuesday, LocalTime.of(8, 0), LocalTime.of(9, 0), Course("MAT-1", null)))

        assertEquals(draft, ScheduleTimeAutofill.fill(listOf(draft), schedule).single())
    }

    @Test fun groupMatchFillsTime() {
        val draft = missingTime(course = Course("MAT-1", "Matemática"))
        val groups = listOf(CourseGroup("01", setOf(DayOfWeek.MONDAY), LocalTime.of(13, 0), LocalTime.of(14, 30)))

        val result = ScheduleTimeAutofill.fill(listOf(draft), groups, Course("mat-1", "Matemática")).single()

        assertEquals(LocalTime.of(13, 0), result.startTime)
        assertEquals(LocalTime.of(14, 30), result.endTime)
        assertTrue(ImportIssue.INFERRED_TIME in result.issues)
    }

    private fun missingTime(course: Course?) = CalendarEventDraft(
        id = "draft",
        title = "Matemática",
        category = null,
        institution = null,
        date = monday,
        startTime = null,
        endTime = null,
        location = null,
        course = course,
        sourcePage = null,
        evidence = null,
        issues = setOf(ImportIssue.MISSING_TIME),
    )

    private fun session(date: LocalDate, start: LocalTime, end: LocalTime, course: Course) = CalendarEventDraft(
        id = "$date-$start",
        title = course.name,
        category = null,
        institution = null,
        date = date,
        startTime = start,
        endTime = end,
        location = null,
        course = course,
        sourcePage = null,
        evidence = null,
    )
}
