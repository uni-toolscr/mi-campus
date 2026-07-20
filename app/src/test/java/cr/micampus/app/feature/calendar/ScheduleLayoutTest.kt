package cr.micampus.app.feature.calendar

import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class ScheduleLayoutTest {
    @Test fun deriveSlotsDeduplicatesAndSortsClasses() {
        val algebraMonday = event("a1", " Álgebra ", 2026, 8, 3, 10, 0, 12, 0)
        val algebraNextMonday = event("a2", " Álgebra ", 2026, 8, 10, 10, 0, 12, 0)
        val biologyTuesday = event("b1", "Biología", 2026, 8, 4, 8, 0, 9, 0)

        val slots = deriveScheduleSlots(listOf(algebraMonday, algebraNextMonday, biologyTuesday), LocalDateTime.of(2026, 8, 1, 0, 0))

        assertEquals(2, slots.size)
        assertEquals(listOf("Biología", "Álgebra"), slots.map(ScheduleSlot::title))
        assertEquals("a1", slots.last().event.id)
    }

    @Test fun deriveSlotsIgnoresNonClassKinds() {
        val monday = event("m1", "Cálculo", 2026, 7, 13, 8, 0, 10, 0)
        val nextMonday = event("m2", "Cálculo", 2026, 7, 20, 8, 0, 10, 0)
        val examSameSlot = event("e1", "Cálculo", 2026, 7, 13, 8, 0, 10, 0).copy(kind = EventKind.EXAM)

        val slots = deriveScheduleSlots(listOf(monday, nextMonday, examSameSlot), LocalDateTime.of(2026, 7, 1, 0, 0))

        assertEquals(1, slots.size)
        assertEquals("Cálculo", slots.single().title)
    }

    @Test fun deriveSlotsSortsByStartThenTitle() {
        val zoology = event("z1", "Zoología", 2026, 8, 3, 10, 0, 11, 0)
        val algebraEarly = event("a1", "Álgebra", 2026, 8, 3, 8, 0, 9, 0)
        val algebraLate = event("a2", "Álgebra", 2026, 8, 5, 10, 0, 11, 0)

        val slots = deriveScheduleSlots(listOf(zoology, algebraEarly, algebraLate), LocalDateTime.of(2026, 8, 1, 0, 0))

        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(10, 0)), slots.map(ScheduleSlot::start))
        assertEquals(listOf("Álgebra", "Zoología", "Álgebra"), slots.map(ScheduleSlot::title))
    }

    @Test fun deriveSlotsRetainsCoursesSharingDayAndTimeRange() {
        val calculus = event("c1", "Cálculo", 2026, 8, 3, 8, 0, 10, 0)
        val physics = event("p1", "Física", 2026, 8, 3, 8, 0, 10, 0)

        val slots = deriveScheduleSlots(listOf(calculus, physics), LocalDateTime.of(2026, 8, 1, 0, 0))

        assertEquals(listOf("Cálculo", "Física"), slots.map(ScheduleSlot::title))
        assertEquals(1, slots.map { it.day to (it.start to it.end) }.distinct().size)
    }

    @Test fun formats12And24HourTime() {
        val time = LocalTime.of(13, 5)
        assertEquals("13:05", time.format(timeFormatter(false)))
        assertTrue(time.format(timeFormatter(true)).contains("1:05"))
    }

    @Test fun axisBoundsRoundAndFallback() {
        val slots = listOf(
            slot("Temprano", DayOfWeek.MONDAY, 8, 15, 9, 0),
            slot("Tarde", DayOfWeek.TUESDAY, 16, 0, 17, 45),
        )

        assertEquals(ScheduleAxisBounds(LocalTime.of(8, 0), LocalTime.of(18, 0)), scheduleAxisBounds(slots))
        assertEquals(ScheduleAxisBounds(LocalTime.of(7, 0), LocalTime.of(21, 0)), scheduleAxisBounds(emptyList()))
    }

    @Test fun axisClampsLateNightEndWithoutWrappingPastMidnight() {
        val bounds = scheduleAxisBounds(listOf(slot("Noche", DayOfWeek.MONDAY, 22, 0, 23, 30)))
        assertEquals(LocalTime.of(22, 0), bounds.start)
        assertEquals(LocalTime.of(23, 59), bounds.end)
        assertTrue(bounds.end.isAfter(bounds.start))
    }

    @Test fun midnightCrossingClassIsExcludedFromGrid() {
        val overnight = CampusEvent(
            id = "n1", title = "Nocturno", institution = Institution.UCR, kind = EventKind.CLASS,
            start = LocalDateTime.of(2026, 8, 3, 23, 0), end = LocalDateTime.of(2026, 8, 4, 1, 0), // next-day 01:00
        )
        assertTrue(deriveScheduleSlots(listOf(overnight), LocalDateTime.of(2026, 8, 1, 0, 0)).isEmpty())
    }

    @Test fun assignCourseColorsWrapsOutOfRangeStoredIndex() {
        val assigned = assignCourseColors(listOf("Curso A"), mapOf("Curso A" to CourseStyle("Curso A", 99, null)), 8)
        assertEquals(3, assigned.getValue("Curso A")) // 99 mod 8
    }

    @Test fun colorsAreDistinctStableAndHonorStoredOverrides() {
        val courses = listOf("Curso C", "Curso A", "Curso E", "Curso B", "Curso D")
        val initial = assignCourseColors(courses, emptyMap(), 8)
        val expanded = assignCourseColors(courses + "Curso F", emptyMap(), 8)
        val stored = assignCourseColors(courses, mapOf("Curso C" to CourseStyle("Curso C", 6, "📚")), 8)

        assertEquals(5, initial.values.toSet().size)
        assertEquals(initial, assignCourseColors(courses, emptyMap(), 8))
        courses.forEach { assertEquals(initial[it], expanded[it]) }
        assertEquals(6, stored["Curso C"])
    }

    @Test fun lanePackingSeparatesOverlapsAndReusesLane() {
        val first = slot("Primera", DayOfWeek.MONDAY, 8, 0, 10, 0)
        val overlap = slot("Cruce", DayOfWeek.MONDAY, 9, 0, 11, 0)
        val later = slot("Después", DayOfWeek.MONDAY, 11, 0, 12, 0)

        val lanes = packLanes(listOf(first, overlap, later)).toMap()

        assertTrue(lanes.getValue(first) != lanes.getValue(overlap))
        assertEquals(0, lanes.getValue(first))
        assertEquals(0, lanes.getValue(later))
    }

    private fun event(id: String, title: String, year: Int, month: Int, day: Int, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) = CampusEvent(
        id = id,
        title = title,
        institution = Institution.UCR,
        kind = EventKind.CLASS,
        start = LocalDateTime.of(year, month, day, startHour, startMinute),
        end = LocalDateTime.of(year, month, day, endHour, endMinute),
    )

    private fun slot(title: String, day: DayOfWeek, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): ScheduleSlot {
        val event = event(title, title, 2026, 8, day.value, startHour, startMinute, endHour, endMinute)
        return ScheduleSlot(event, title, day, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute))
    }
}
