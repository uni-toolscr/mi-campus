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
    fun `keeps upcoming and in-progress classes today, drops everything else`() {
        val upcoming = classAt("upcoming", now.plusHours(1), now.plusHours(3))
        val inProgress = classAt("in-progress", now.minusHours(1), now.plusMinutes(30))
        val endedToday = classAt("ended", now.minusHours(4), now.minusHours(2))
        val tomorrow = classAt("tomorrow", now.plusDays(1), now.plusDays(1).plusHours(2))
        val yesterday = classAt("yesterday", now.minusDays(1), now.minusDays(1).plusHours(2))

        val result = todaysClasses(listOf(upcoming, inProgress, endedToday, tomorrow, yesterday), now)

        assertEquals(listOf(upcoming, inProgress), result)
    }

    @Test
    fun `class ending exactly now is still included`() {
        val endingNow = classAt("ending-now", now.minusHours(2), now)
        assertEquals(listOf(endingNow), todaysClasses(listOf(endingNow), now))
    }

    @Test
    fun `empty when no classes today`() {
        val tomorrow = classAt("tomorrow", now.plusDays(1), now.plusDays(1).plusHours(2))
        assertEquals(emptyList<CampusEvent>(), todaysClasses(listOf(tomorrow), now))
    }
}
