package cr.micampus.app.feature.calendar

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class CalendarListsTest {
    // Wednesday 28 Oct 2026; its week runs Mon 26 Oct – Sun 1 Nov.
    private val today = LocalDate.of(2026, 10, 28)
    private val weekStart = LocalDate.of(2026, 10, 26)
    private val now = today.atTime(9, 0)

    @Test fun weekRangeLabelSpansMonths() {
        assertEquals("26 oct – 1 nov", weekRangeLabel(weekStart))
    }

    @Test fun weekStripMarksClassesDeadlinesTodayAndPast() {
        val events = listOf(
            event("c1", "Cálculo II", EventKind.CLASS, 26, 10),
            event("c2", "Física", EventKind.CLASS, 26, 8),
            event("t1", "Tarea 3", EventKind.TAREA, 28, 23),
            event("a1", "Charla", EventKind.ACTIVITY, 29, 14),
        )

        val strip = weekStripDays(weekStart, events, today)

        assertEquals(7, strip.size)
        assertEquals(listOf("Física", "Cálculo II"), strip[0].classKeys)
        assertTrue(strip[0].isPast)
        assertFalse(strip[0].hasDeadline)
        assertTrue(strip[2].isToday)
        assertTrue(strip[2].hasDeadline)
        assertFalse(strip[3].hasDeadline)
    }

    @Test fun weekListFoldsPastDaysOfTheCurrentWeek() {
        val events = listOf(
            event("c1", "Cálculo II", EventKind.CLASS, 26, 10),
            event("c2", "Física", EventKind.CLASS, 27, 8),
            event("c3", "Física", EventKind.CLASS, 27, 13),
            event("e1", "Parcial", EventKind.EXAM, 28, 10),
            event("c4", "Cálculo II", EventKind.CLASS, 28, 8),
        )

        val folded = weekListRows(weekStart, events, today, showPast = false)

        val fold = folded.first() as WeekListRow.PastFold
        assertEquals(WeekListRow.PastFold(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 27), 3, 0, false), fold)
        assertEquals("Lun 26 – Mar 27 · 3 clases", pastFoldLabel(fold))
        assertEquals(WeekListRow.DayHeader(today), folded[1])
        assertEquals(listOf("c4"), (folded[2] as WeekListRow.Classes).classes.map(CampusEvent::id))
        assertEquals(WeekListRow.Event(events[3], 0, 1), folded[3])
        assertEquals(WeekListRow.DayHeader(today.plusDays(1)), folded[4])
        assertEquals(WeekListRow.Empty(today.plusDays(1)), folded[5])
        assertEquals(5, folded.count { it is WeekListRow.DayHeader })

        val expanded = weekListRows(weekStart, events, today, showPast = true)
        assertTrue((expanded.first() as WeekListRow.PastFold).expanded)
        assertEquals(7, expanded.count { it is WeekListRow.DayHeader })
    }

    @Test fun otherWeeksHaveNoFold() {
        assertTrue(weekListRows(weekStart.plusDays(7), emptyList(), today, showPast = false).none { it is WeekListRow.PastFold })
        assertTrue(weekListRows(weekStart.minusDays(7), emptyList(), today, showPast = false).none { it is WeekListRow.PastFold })
        // Monday of the current week: nothing is past yet.
        assertTrue(weekListRows(weekStart, emptyList(), weekStart, showPast = false).none { it is WeekListRow.PastFold })
    }

    @Test fun pastFoldLabelHandlesSingleDayAndEmptyDays() {
        assertEquals("Lun 26 · Sin actividades", pastFoldLabel(WeekListRow.PastFold(weekStart, weekStart, 0, 0, false)))
        assertEquals("Lun 26 · 1 clase · 2 eventos", pastFoldLabel(WeekListRow.PastFold(weekStart, weekStart, 1, 2, false)))
    }

    @Test fun weekDayHeaderUsesRelativeNames() {
        assertEquals("Hoy miércoles 28", weekDayHeader(today, today))
        assertEquals("Mañana jueves 29", weekDayHeader(today.plusDays(1), today))
        assertEquals("Viernes 30", weekDayHeader(today.plusDays(2), today))
    }

    @Test fun weekEventBadgeByKind() {
        assertEquals("Entrega", weekEventBadge(EventKind.TAREA))
        assertEquals("Examen", weekEventBadge(EventKind.EXAM))
        assertEquals("Quiz", weekEventBadge(EventKind.QUIZ))
        assertNull(weekEventBadge(EventKind.ACTIVITY))
    }

    @Test fun agendaGroupsByDayWithMonthDividers() {
        val events = listOf(
            event("b", "B", EventKind.EXAM, 29, 10),
            event("a", "A", EventKind.TAREA, 29, 8),
            event("n", "N", EventKind.QUIZ, 31, 10).copy(start = LocalDateTime.of(2026, 11, 2, 10, 0), end = LocalDateTime.of(2026, 11, 2, 11, 0)),
        )

        val rows = agendaRows(events, now, pendingOnly = true, showPast = false)

        assertEquals(
            listOf(
                AgendaRow.DayHeader(today.plusDays(1)),
                AgendaRow.Event(events[1], 0, 2, false),
                AgendaRow.Event(events[0], 1, 2, false),
                AgendaRow.Month(YearMonth.of(2026, 11)),
                AgendaRow.DayHeader(LocalDate.of(2026, 11, 2)),
                AgendaRow.Event(events[2], 0, 1, false),
            ),
            rows,
        )
    }

    @Test fun agendaFoldsPastEventsWhenPendingOnly() {
        val past = event("p", "Pasado", EventKind.EXAM, 27, 10)
        val upcoming = event("u", "Próximo", EventKind.EXAM, 29, 10)

        val folded = agendaRows(listOf(past, upcoming), now, pendingOnly = true, showPast = false)
        assertEquals(AgendaRow.PastFold(1, false), folded.first())
        assertTrue(folded.none { it is AgendaRow.Event && it.event.id == "p" })

        val shown = agendaRows(listOf(past, upcoming), now, pendingOnly = true, showPast = true)
        assertEquals(AgendaRow.PastFold(1, true), shown.first())
        assertEquals(AgendaRow.Event(past, 0, 1, true), shown[2])

        val all = agendaRows(listOf(past, upcoming), now, pendingOnly = false, showPast = false)
        assertTrue(all.none { it is AgendaRow.PastFold })
        assertEquals(2, all.count { it is AgendaRow.Event })
    }

    @Test fun agendaShowsDividerBeforeAnEarlierMonth() {
        val august = event("old", "Viejo", EventKind.EXAM, 1, 10).copy(start = LocalDateTime.of(2026, 8, 3, 8, 0), end = LocalDateTime.of(2026, 8, 3, 9, 0))

        val rows = agendaRows(listOf(august), now, pendingOnly = false, showPast = false)

        assertEquals(AgendaRow.Month(YearMonth.of(2026, 8)), rows.first())
    }

    @Test fun agendaDayHeaderIsUppercaseAndRelative() {
        assertEquals("HOY · MIÉ 28 OCT", agendaDayHeader(today, today))
        assertEquals("MAÑANA · JUE 29 OCT", agendaDayHeader(today.plusDays(1), today))
        assertEquals("VIERNES 30 OCT", agendaDayHeader(today.plusDays(2), today))
    }

    @Test fun monthDividerAddsYearOutsideTheCurrentOne() {
        assertEquals("Noviembre", monthDividerLabel(YearMonth.of(2026, 11), today))
        assertEquals("Enero 2027", monthDividerLabel(YearMonth.of(2027, 1), today))
    }

    @Test fun countdownBadges() {
        fun at(start: LocalDateTime) = CampusEvent("x", "X", Institution.UCR, EventKind.EXAM, start, start.plusHours(1))

        assertNull(agendaCountdown(at(now.minusHours(2)), now))
        assertEquals(Countdown("Ahora", true), agendaCountdown(at(now.minusMinutes(30)), now))
        assertEquals(Countdown("en 25 min", true), agendaCountdown(at(now.plusMinutes(25)), now))
        assertEquals(Countdown("en 3 h", true), agendaCountdown(at(now.plusHours(3)), now))
        assertEquals(Countdown("Mañana", true), agendaCountdown(at(now.plusDays(1)), now))
        assertEquals(Countdown("en 2 días", false), agendaCountdown(at(now.plusDays(2)), now))
        assertEquals(Countdown("Hoy", true), agendaCountdown(at(today.atTime(13, 0)), today.atTime(0, 30)))
    }

    @Test fun courseKeyMatchesByCodeThenByTitle() {
        val calculus = event("c", "Cálculo II", EventKind.CLASS, 26, 10).copy(courseCode = "MA-1002")
        val physics = event("f", "Física", EventKind.CLASS, 26, 12)
        val byCode = classTitlesByCode(listOf(calculus, physics))
        val titles = listOf("Cálculo II", "Física")

        assertEquals(mapOf("ma-1002" to "Cálculo II"), byCode)
        assertEquals("Cálculo II", courseKeyOf(event("e", "Parcial", EventKind.EXAM, 28, 10).copy(courseCode = " MA-1002 "), byCode, titles))
        assertEquals("Física", courseKeyOf(event("t", "Tarea de física", EventKind.TAREA, 28, 10), byCode, titles))
        assertEquals("Física", courseKeyOf(physics, byCode, titles))
        assertNull(courseKeyOf(event("o", "Charla", EventKind.ACTIVITY, 28, 10), byCode, titles))
    }

    private fun event(id: String, title: String, kind: EventKind, day: Int, hour: Int): CampusEvent {
        val start = LocalDateTime.of(2026, 10, day, hour, 0)
        return CampusEvent(id, title, Institution.UCR, kind, start, start.plusHours(1))
    }
}
