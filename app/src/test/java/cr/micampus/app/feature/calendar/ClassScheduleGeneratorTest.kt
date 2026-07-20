package cr.micampus.app.feature.calendar

import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ClassScheduleGeneratorTest {
    // 2026-03-02 is a Monday; four full weeks through Sunday 2026-03-29.
    private val semesterStart = LocalDate.of(2026, 3, 2)
    private val semesterEnd = LocalDate.of(2026, 3, 29)

    private fun spec(
        blocks: List<ClassBlock>,
        title: String = "Cálculo I",
        start: LocalDate = semesterStart,
        end: LocalDate = semesterEnd,
    ) = ClassScheduleSpec(title, Institution.UCR, "Aula 101", "Grupo 02", start, end, blocks)

    @Test
    fun `two blocks with different days and times generate independent sessions`() {
        val events = ClassScheduleGenerator.generate(
            spec(
                listOf(
                    ClassBlock(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 40)),
                    ClassBlock(DayOfWeek.THURSDAY, LocalTime.of(13, 0), LocalTime.of(14, 40)),
                ),
            ),
        )
        assertEquals(8, events.size)
        assertTrue(events.all { it.kind == EventKind.CLASS && it.title == "Cálculo I" && it.location == "Aula 101" })
        val tuesdays = events.filter { it.start.dayOfWeek == DayOfWeek.TUESDAY }
        val thursdays = events.filter { it.start.dayOfWeek == DayOfWeek.THURSDAY }
        assertEquals(4, tuesdays.size)
        assertEquals(4, thursdays.size)
        assertTrue(tuesdays.all { it.start.toLocalTime() == LocalTime.of(8, 0) && it.end.toLocalTime() == LocalTime.of(9, 40) })
        assertTrue(thursdays.all { it.start.toLocalTime() == LocalTime.of(13, 0) && it.end.toLocalTime() == LocalTime.of(14, 40) })
        assertEquals(events.sortedBy { it.start }, events)
    }

    @Test
    fun `semester boundaries are inclusive`() {
        // Start (Monday) and end (Sunday) both match blocks on their own day.
        val events = ClassScheduleGenerator.generate(
            spec(
                listOf(
                    ClassBlock(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(8, 50)),
                    ClassBlock(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(10, 50)),
                ),
            ),
        )
        assertTrue(events.any { it.start.toLocalDate() == semesterStart })
        assertTrue(events.any { it.start.toLocalDate() == semesterEnd })
    }

    @Test
    fun `ids are stable across regeneration and unique per session`() {
        val blocks = listOf(ClassBlock(DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(11, 50)))
        val first = ClassScheduleGenerator.generate(spec(blocks))
        val second = ClassScheduleGenerator.generate(spec(blocks))
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first.size, first.map { it.id }.toSet().size)
        assertTrue(first.all { it.id.startsWith("clase-") })
    }

    @Test
    fun `invalid specs generate nothing`() {
        val block = ClassBlock(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0))
        // Blank title.
        assertTrue(ClassScheduleGenerator.generate(spec(listOf(block), title = "  ")).isEmpty())
        // End before start of semester.
        assertTrue(ClassScheduleGenerator.generate(spec(listOf(block), start = semesterEnd, end = semesterStart)).isEmpty())
        // No blocks.
        assertTrue(ClassScheduleGenerator.generate(spec(emptyList())).isEmpty())
        // Block end not after start.
        assertTrue(ClassScheduleGenerator.generate(spec(listOf(ClassBlock(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 0))))).isEmpty())
        // Duplicate blocks would collide on stable ids; the spec rejects them.
        assertTrue(ClassScheduleGenerator.generate(spec(listOf(block, block.copy()))).isEmpty())
    }

    @Test
    fun `no sessions when the block day never occurs in the range`() {
        // Tuesday block inside a Monday-only range.
        val events = ClassScheduleGenerator.generate(
            spec(
                listOf(ClassBlock(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 0))),
                start = semesterStart,
                end = semesterStart,
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `title location and notes are trimmed`() {
        val events = ClassScheduleGenerator.generate(
            ClassScheduleSpec("  Física  ", Institution.UNA, " Lab 2 ", " Teoría ", semesterStart, semesterStart, listOf(ClassBlock(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0)))),
        )
        assertEquals(1, events.size)
        assertEquals("Física", events.single().title)
        assertEquals("Lab 2", events.single().location)
        assertEquals("Teoría", events.single().notes)
        assertEquals(Institution.UNA, events.single().institution)
    }
}
