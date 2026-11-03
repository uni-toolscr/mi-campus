package cr.micampus.app.feature.home

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.institution.UpcomingDeparture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HomeDashboardTest {
    // Wednesday 28 October 2026, 9:35.
    private val now = LocalDateTime.of(2026, 10, 28, 9, 35)

    @Test fun heroPrefersTheClassInProgress() {
        val events = listOf(
            event("later", EventKind.CLASS, now.plusHours(2)),
            event("current", EventKind.CLASS, now.minusMinutes(20)),
            event("exam", EventKind.EXAM, now.plusMinutes(5)),
        )
        val hero = homeHero(events, now)!!
        assertEquals("current", hero.event.id)
        assertEquals(HeroTiming.Now, hero.timing)
        assertEquals("CLASE EN CURSO", heroOverline(hero.event.kind, hero.timing))
    }

    @Test fun heroCountsDownToTheNextClassToday() {
        val events = listOf(
            event("past", EventKind.CLASS, now.minusHours(3)),
            event("next", EventKind.CLASS, now.plusMinutes(25)),
        )
        val hero = homeHero(events, now)!!
        assertEquals("next", hero.event.id)
        assertEquals(HeroTiming.StartsIn(25), hero.timing)
    }

    @Test fun heroFallsBackToTheNextClassOnALaterDay() {
        val tomorrow = LocalDateTime.of(2026, 10, 29, 8, 0)
        val hero = homeHero(listOf(event("tomorrow", EventKind.CLASS, tomorrow)), now)!!
        assertEquals(HeroTiming.Later(tomorrow), hero.timing)
    }

    @Test fun heroUsesTheNextNonClassEventWithoutUpcomingClasses() {
        val events = listOf(
            event("old-class", EventKind.CLASS, now.minusDays(1)),
            event("tarea", EventKind.TAREA, now.plusDays(2)),
        )
        val hero = homeHero(events, now)!!
        assertEquals("tarea", hero.event.id)
        assertEquals("PRÓXIMA ENTREGA", heroOverline(hero.event.kind, hero.timing))
    }

    @Test fun heroIsNullWithoutAnyUpcomingEvent() {
        assertNull(homeHero(listOf(event("old", EventKind.EXAM, now.minusDays(1))), now))
    }

    @Test fun heroTimingLabels() {
        val today = now.toLocalDate()
        assertEquals("Ahora", heroTimingLabel(HeroTiming.Now, today, use12h = false))
        assertEquals("en 25 min", heroTimingLabel(HeroTiming.StartsIn(25), today, use12h = false))
        assertEquals("en 1 h 5 min", heroTimingLabel(HeroTiming.StartsIn(65), today, use12h = false))
        assertEquals("Mañana 08:00", heroTimingLabel(HeroTiming.Later(LocalDateTime.of(2026, 10, 29, 8, 0)), today, use12h = false))
        assertEquals("Vie 08:00", heroTimingLabel(HeroTiming.Later(LocalDateTime.of(2026, 10, 30, 8, 0)), today, use12h = false))
    }

    @Test fun heroTimeLineJoinsRangeAndLocation() {
        val event = event("c", EventKind.CLASS, LocalDateTime.of(2026, 10, 28, 10, 0)).copy(end = LocalDateTime.of(2026, 10, 28, 11, 50), location = "Aula 204")
        assertEquals("10:00 – 11:50 · Aula 204", heroTimeLine(event, use12h = false))
        val deadline = event("t", EventKind.TAREA, LocalDateTime.of(2026, 10, 28, 23, 59)).let { it.copy(end = it.start) }
        assertEquals("23:59", heroTimeLine(deadline, use12h = false))
    }

    @Test fun weekSummaryCountsPendingNonClassEventsUntilSunday() {
        val events = listOf(
            event("class", EventKind.CLASS, now.plusHours(1)),
            event("done", EventKind.TAREA, now.minusDays(1)),
            event("exam", EventKind.EXAM, LocalDateTime.of(2026, 10, 30, 10, 0)),
            event("quiz", EventKind.QUIZ, LocalDateTime.of(2026, 11, 1, 23, 0)),
            event("tarea", EventKind.TAREA, now.plusHours(3)),
            event("next-week", EventKind.EXAM, LocalDateTime.of(2026, 11, 2, 0, 0)),
        )
        val week = weekSummary(events, now)
        assertEquals(WeekSummary(pending = 3, exams = 1, quizzes = 1), week)
        assertEquals("1 examen · 1 quiz", weekEvaluationsLabel(week))
        assertEquals("2 exámenes", weekEvaluationsLabel(WeekSummary(pending = 2, exams = 2)))
        assertNull(weekEvaluationsLabel(WeekSummary(pending = 2)))
    }

    @Test fun weekSummaryOnSundayOnlyCoversToday() {
        val sunday = LocalDateTime.of(2026, 11, 1, 12, 0)
        val events = listOf(event("today", EventKind.TAREA, sunday.plusHours(2)), event("monday", EventKind.TAREA, sunday.plusDays(1)))
        assertEquals(1, weekSummary(events, sunday).pending)
    }

    @Test fun eventsMatchCoursesByCodeThenByTitle() {
        val courses = listOf(HomeCourse("Cálculo I", "MA1001"), HomeCourse("Física", null), HomeCourse("Física General", null))
        assertEquals("Cálculo I", matchCourseKey(event("e", EventKind.EXAM, now).copy(title = "Parcial", courseCode = "ma1001"), courses))
        assertEquals("Física General", matchCourseKey(event("e", EventKind.TAREA, now).copy(title = "Tarea 2 de física general"), courses))
        assertEquals("Física", matchCourseKey(event("e", EventKind.QUIZ, now).copy(title = "Quiz Física"), courses))
        assertNull(matchCourseKey(event("e", EventKind.TAREA, now).copy(title = "Matrícula"), courses))
    }

    @Test fun homeCoursesUseHorarioKeysWithTheirCodes() {
        val events = listOf(
            event("c1", EventKind.CLASS, now.plusDays(1)).copy(title = " Cálculo I "),
            event("c2", EventKind.CLASS, now.plusDays(8)).copy(title = "Cálculo I", courseCode = "MA1001"),
            event("x", EventKind.EXAM, now.plusDays(2)),
        )
        assertEquals(listOf(HomeCourse("Cálculo I", "MA1001")), homeCourses(events, now))
    }

    @Test fun badgesPreferUrgencyOverKind() {
        val today = now.toLocalDate()
        assertEquals(HomeEventBadge.TODAY, homeEventBadge(event("e", EventKind.EXAM, now.plusHours(2)), today))
        assertEquals(HomeEventBadge.TOMORROW, homeEventBadge(event("e", EventKind.TAREA, now.plusDays(1)), today))
        assertEquals(HomeEventBadge.EXAM, homeEventBadge(event("e", EventKind.EXAM, now.plusDays(2)), today))
        assertEquals(HomeEventBadge.QUIZ, homeEventBadge(event("e", EventKind.QUIZ, now.plusDays(3)), today))
        assertNull(homeEventBadge(event("e", EventKind.TAREA, now.plusDays(2)), today))
    }

    @Test fun stateKeepsClassesOutOfTheListAndAvoidsRepeatingTheHero() {
        val events = listOf(event("class", EventKind.CLASS, now.plusHours(1))) +
            (1..6).map { event("e$it", EventKind.TAREA, now.plusDays(it.toLong())) }
        val withClass = homeState(events, emptyList(), null, use12hClock = false, now = now)
        assertEquals("class", withClass.hero?.event?.id)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), withClass.events.map { it.event.id })

        val withoutClass = homeState(events.drop(1), emptyList(), null, use12hClock = false, now = now)
        assertEquals("e1", withoutClass.hero?.event?.id)
        assertEquals(listOf("e2", "e3", "e4", "e5", "e6"), withoutClass.events.map { it.event.id })
    }

    @Test fun busLabels() {
        val ucr = UpcomingDeparture(Institution.UCR, "d", "UCR", "Heredia", now)
        val una = UpcomingDeparture(Institution.UNA, "d", "Benjamín Núñez", "Omar Dengo", now)
        assertEquals("Bus UCR → Heredia", busRouteLabel(ucr))
        assertEquals("Bus UNA · Benjamín Núñez → Omar Dengo", busRouteLabel(una))
        assertEquals("en 25 min", busCountdownLabel(25))
        assertEquals("Saliendo", busCountdownLabel(0))
    }

    @Test fun headerDateIsCapitalizedSpanish() {
        assertEquals("Miércoles 28 de octubre", homeDateLabel(LocalDate.of(2026, 10, 28)))
    }

    @Test fun completedTasksStopCountingAndNeverBecomeTheHero() {
        val done = event("done", EventKind.TAREA, now.plusHours(3)).copy(completed = true)
        val open = event("open", EventKind.TAREA, now.plusHours(5))
        assertEquals(1, weekSummary(listOf(done, open), now).pending)
        assertEquals("open", homeHero(listOf(done, open), now)?.event?.id)
    }

    @Test fun classHeroCarriesItsCourseAndNextEvaluation() {
        val classTime = now.plusHours(1)
        val events = listOf(
            event("c1", EventKind.CLASS, classTime).copy(title = "Química General", courseCode = "QU-0100"),
            event("q2", EventKind.QUIZ, now.plusDays(1)).copy(title = "Quiz 2 · Química", courseCode = "QU-0100"),
            event("far", EventKind.EXAM, now.plusDays(9)).copy(title = "Parcial · Química", courseCode = "QU-0100"),
        )
        val hero = homeState(events, emptyList(), null, use12hClock = false, now = now).hero!!
        assertEquals("Química General", hero.courseKey)
        assertEquals("QU-0100", hero.courseCode)
        assertEquals("q2", hero.evaluation?.id)
        assertEquals("Quiz 2 mañana", heroEvaluationLabel(hero.evaluation!!, now.toLocalDate()))
        assertEquals("Quiz 2 el viernes", heroEvaluationLabel(hero.evaluation!!.copy(start = now.plusDays(2)), now.toLocalDate()))
    }

    @Test fun emptyDatabaseShowsTheChecklist() {
        val state = homeState(emptyList(), emptyList(), null, use12hClock = false, now = now, suggestAulaVirtual = true)
        assertEquals(true, state.gettingStarted)
        assertEquals(true, state.suggestAulaVirtual)
    }

    @Test fun busLineSaysWhatComesNext() {
        val next = UpcomingDeparture(Institution.UCR, "d", "UCR", "Heredia", now.plusMinutes(18))
        assertEquals("en 18 min · luego 10:30", busSupportingLabel(HomeBus(next, next.copy(departure = now.withHour(10).withMinute(30))), now, use12h = false))
        assertEquals("en 18 min · última de hoy", busSupportingLabel(HomeBus(next, null), now, use12h = false))
    }

    private fun event(id: String, kind: EventKind, start: LocalDateTime) = CampusEvent(
        id = id,
        title = id,
        institution = Institution.UCR,
        kind = kind,
        start = start,
        end = start.plusHours(1),
    )
}
