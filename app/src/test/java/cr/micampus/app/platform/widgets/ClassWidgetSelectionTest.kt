package cr.micampus.app.platform.widgets

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassWidgetSelectionTest {
    private val now = LocalDateTime.of(2026, 7, 20, 12, 0)

    private fun classAt(id: String, start: LocalDateTime, end: LocalDateTime) = CampusEvent(
        id = id,
        title = id,
        institution = Institution.UCR,
        kind = EventKind.CLASS,
        start = start,
        end = end,
    )

    @Test
    fun `keeps classes that have not ended, soonest first, so the one in progress leads`() {
        val upcoming = classAt("upcoming", now.plusHours(1), now.plusHours(3))
        val inProgress = classAt("in-progress", now.minusHours(1), now.plusMinutes(30))
        val endedToday = classAt("ended", now.minusHours(4), now.minusHours(2))
        val tomorrow = classAt("tomorrow", now.plusDays(1), now.plusDays(1).plusHours(2))
        val yesterday = classAt("yesterday", now.minusDays(1), now.minusDays(1).plusHours(2))

        val result = upcomingClasses(listOf(tomorrow, upcoming, inProgress, endedToday, yesterday), now)

        assertEquals(listOf(inProgress, upcoming, tomorrow), result)
    }

    @Test
    fun `class ending exactly now has handed over to the next one`() {
        val endingNow = classAt("ending-now", now.minusHours(2), now)
        assertEquals(emptyList<CampusEvent>(), upcomingClasses(listOf(endingNow), now))
    }

    @Test
    fun `non-class events never lead the class widget`() {
        val exam = classAt("exam", now.plusHours(1), now.plusHours(2)).copy(kind = EventKind.EXAM)
        assertEquals(emptyList<CampusEvent>(), upcomingClasses(listOf(exam), now))
    }
}
