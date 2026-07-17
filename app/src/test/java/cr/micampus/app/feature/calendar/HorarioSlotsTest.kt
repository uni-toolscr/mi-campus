package cr.micampus.app.feature.calendar

import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class HorarioSlotsTest {
    @Test
    fun `dedupes weekly class occurrences and ignores non classes`() {
        val monday = event("Cálculo", EventKind.CLASS, "2026-07-13T08:00", "2026-07-13T10:00")
        val nextMonday = event("Cálculo", EventKind.CLASS, "2026-07-20T08:00", "2026-07-20T10:00")
        val exam = event("Cálculo", EventKind.EXAM, "2026-07-13T08:00", "2026-07-13T10:00")

        assertEquals(listOf(ScheduleSlot("Cálculo", monday.start.dayOfWeek, LocalTime.of(8, 0), LocalTime.of(10, 0))), deriveScheduleSlots(listOf(monday, nextMonday, exam)))
    }

    @Test
    fun `separates course times and sorts by start then title`() {
        val events = listOf(
            event("Zoología", EventKind.CLASS, "2026-07-13T10:00", "2026-07-13T11:00"),
            event("Álgebra", EventKind.CLASS, "2026-07-13T08:00", "2026-07-13T09:00"),
            event("Álgebra", EventKind.CLASS, "2026-07-15T10:00", "2026-07-15T11:00"),
        )

        val slots = deriveScheduleSlots(events)

        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(10, 0)), slots.map(ScheduleSlot::start))
        assertEquals(listOf("Álgebra", "Zoología", "Álgebra"), slots.map(ScheduleSlot::title))
    }

    @Test
    fun `retains courses sharing a day and time range`() {
        val calculus = event("Cálculo", EventKind.CLASS, "2026-07-13T08:00", "2026-07-13T10:00")
        val physics = event("Física", EventKind.CLASS, "2026-07-13T08:00", "2026-07-13T10:00")

        val slots = deriveScheduleSlots(listOf(calculus, physics))

        assertEquals(listOf("Cálculo", "Física"), slots.map(ScheduleSlot::title))
        assertEquals(1, slots.map { it.day to (it.start to it.end) }.distinct().size)
    }

    @Test
    fun `formats 12 and 24 hour time`() {
        val time = LocalTime.of(13, 5)
        assertEquals("13:05", time.format(timeFormatter(false)))
        assertTrue(time.format(timeFormatter(true)).contains("1:05"))
    }

    private fun event(title: String, kind: EventKind, start: String, end: String) = CampusEvent(
        id = "$title-$start",
        title = title,
        institution = Institution.UCR,
        kind = kind,
        start = LocalDateTime.parse(start),
        end = LocalDateTime.parse(end),
    )
}
