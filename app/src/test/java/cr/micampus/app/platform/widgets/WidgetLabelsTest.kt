package cr.micampus.app.platform.widgets

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.institution.UpcomingDeparture
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLabelsTest {
    // Monday 2 Nov 2026, during Física General (14:00–15:50).
    private val now = LocalDateTime.of(2026, 11, 2, 15, 37)
    private val today = now.toLocalDate()

    private fun event(kind: EventKind, start: LocalDateTime, end: LocalDateTime = start.plusHours(2), location: String = "") =
        CampusEvent("id", "Física General", Institution.UCR, kind, start, end, location = location)

    @Test fun `class in progress counts down its end inside the last half hour`() {
        val fisica = event(EventKind.CLASS, now.withHour(14).withMinute(0), now.withHour(15).withMinute(50))
        val state = classHeroState(fisica, now)
        assertEquals(ClassHeroState.InProgress(13), state)
        assertEquals("EN CURSO · FS-0210", classHeroOverline(state, "FS-0210"))
        assertEquals(ShapeBadgeText(null, "13", "min"), classHeroBadge(fisica, state, today, use12h = false))
    }

    @Test fun `long class in progress shows when it ends instead of a stale countdown`() {
        val calculo = event(EventKind.CLASS, now.minusMinutes(10), now.plusMinutes(100))
        val state = classHeroState(calculo, now)
        assertEquals(ClassHeroState.InProgress(null), state)
        assertEquals(ShapeBadgeText("hasta", "17:17", null), classHeroBadge(calculo, state, today, use12h = false))
    }

    @Test fun `next class today and on a later day`() {
        val soon = event(EventKind.CLASS, now.plusMinutes(25))
        assertEquals(ShapeBadgeText("en", "25", "min"), classHeroBadge(soon, classHeroState(soon, now), today, false))
        assertEquals("PRÓXIMA CLASE", classHeroOverline(classHeroState(soon, now), null))

        val later = event(EventKind.CLASS, now.plusHours(2))
        assertEquals(ShapeBadgeText("hoy", "17:37", null), classHeroBadge(later, classHeroState(later, now), today, false))

        val tomorrow = event(EventKind.CLASS, LocalDateTime.of(2026, 11, 3, 8, 0))
        assertEquals(ClassHeroState.Later, classHeroState(tomorrow, now))
        assertEquals(ShapeBadgeText("mañana", "08:00", null), classHeroBadge(tomorrow, ClassHeroState.Later, today, false))
        assertEquals("Mañana 08:00", classRowTrailing(tomorrow, today, false))
        assertEquals("17:37", classRowTrailing(later, today, false))
    }

    @Test fun `12h badges split the meridiem off and later days get a short label`() {
        val quiz = event(EventKind.CLASS, LocalDateTime.of(2026, 11, 4, 14, 0))
        assertEquals(ShapeBadgeText("mié", "2:00", "p. m."), classHeroBadge(quiz, ClassHeroState.Later, today, use12h = true))
        assertEquals("9 nov", widgetDayLabel(today.plusDays(7), today))
        assertEquals("hoy", widgetDayLabel(today, today))
    }

    @Test fun `class countdown refreshes toward its end while in progress and its start before`() {
        val inProgress = event(EventKind.CLASS, now.minusHours(1), now.plusMinutes(13))
        assertEquals(BusWidgetRefreshPlan.RefreshAt(now.plusMinutes(1)), classWidgetRefreshPlan(inProgress, now))

        val tomorrow = event(EventKind.CLASS, LocalDateTime.of(2026, 11, 3, 8, 0))
        assertEquals(BusWidgetRefreshPlan.RefreshAt(LocalDateTime.of(2026, 11, 3, 7, 30)), classWidgetRefreshPlan(tomorrow, now))
    }

    @Test fun `remaining classes today`() {
        val classes = listOf(event(EventKind.CLASS, now.minusHours(1)), event(EventKind.CLASS, now.plusDays(1)))
        assertEquals("1 restante hoy", remainingTodayLabel(classes, today))
        assertEquals("Ninguna más hoy", remainingTodayLabel(classes.drop(1), today))
    }

    @Test fun `event pills prefer urgency, then kind, then distance`() {
        assertEquals(EventPill("Hoy", true), eventPill(event(EventKind.TAREA, now.plusHours(5)), today))
        assertEquals(EventPill("Mañana", true), eventPill(event(EventKind.EXAM, now.plusDays(1)), today))
        assertEquals(EventPill("Quiz", false), eventPill(event(EventKind.QUIZ, now.plusDays(2)), today))
        assertEquals(EventPill("en 9 días", false), eventPill(event(EventKind.ACTIVITY, now.plusDays(9)), today))
    }

    @Test fun `event supporting line is relative and keeps the place`() {
        val quiz = event(EventKind.QUIZ, LocalDateTime.of(2026, 11, 4, 8, 0), location = "Aula 110")
        assertEquals("Mié 08:00 · Aula 110", eventSupporting(quiz, today, use12h = false))
        assertEquals("Hoy 23:00", eventSupporting(event(EventKind.TAREA, now.withHour(23).withMinute(0)), today, false))
        assertEquals("6 pendientes", pendingEventsLabel(6))
        assertEquals("1 pendiente", pendingEventsLabel(1))
    }

    @Test fun `bus route names the institution only when mixing both and the endpoints don't`() {
        val ucr = UpcomingDeparture(Institution.UCR, "d", "Heredia", "UCR", now.plusMinutes(23))
        val una = UpcomingDeparture(Institution.UNA, "d", "Omar Dengo", "Benjamín Núñez", now.plusMinutes(23))
        assertEquals("Heredia → UCR", busRouteLabel(ucr, WIDGET_FILTER_BOTH))
        assertEquals("UNA · Omar Dengo → Benjamín Núñez", busRouteLabel(una, WIDGET_FILTER_BOTH))
        assertEquals("Omar Dengo → Benjamín Núñez", busRouteLabel(una, WIDGET_FILTER_UNA))
    }

    @Test fun `bus countdown label`() {
        assertEquals("en 23 min", busCountdownLabel(now.plusMinutes(23), now))
        assertEquals("Saliendo", busCountdownLabel(now, now))
        assertEquals("Próxima salida", busCountdownLabel(now.plusHours(2), now))
    }
}
