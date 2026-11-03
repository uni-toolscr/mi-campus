package cr.micampus.app.feature.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TransportTimelineTest {
    @Test fun relativeDurationUsesMinutesThenHours() {
        assertEquals("Saliendo", relativeDurationText(0))
        assertEquals("en 25 min", relativeDurationText(25))
        assertEquals("en 1 h", relativeDurationText(60))
        assertEquals("en 1 h 5 min", relativeDurationText(65))
        assertEquals("en 2 h 30 min", relativeDurationText(150))
        assertEquals("en 7 h 53 min", relativeDurationText(7 * 60 + 53))
        assertEquals("en 1 día 8 h", relativeDurationText(32 * 60 + 35))
        assertEquals("en 2 días", relativeDurationText(48 * 60 + 20))
    }

    @Test fun minutesUntilTruncatesAndNeverGoesNegative() {
        val now = LocalDateTime.of(2026, 10, 28, 9, 59, 30)
        assertEquals(0, minutesUntil(now, LocalDateTime.of(2026, 10, 28, 10, 0)))
        assertEquals(25, minutesUntil(now, LocalDateTime.of(2026, 10, 28, 10, 25)))
        assertEquals(0, minutesUntil(now, LocalDateTime.of(2026, 10, 28, 9, 0)))
    }

    @Test fun dateLabelsAreRelativeForTodayAndTomorrow() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals("Hoy", transportDateLabel(today, today))
        assertEquals("Mañana", transportDateLabel(today.plusDays(1), today))
        assertEquals("vie 30 oct", transportDateLabel(today.plusDays(2), today))
    }

    @Test fun departedRowsFoldExceptTheLatestOne() {
        assertEquals(13, foldedDepartureCount(total = 17, nextIndex = 14, isToday = true, showDeparted = false))
        assertEquals(16, foldedDepartureCount(total = 17, nextIndex = -1, isToday = true, showDeparted = false))
        assertEquals(0, foldedDepartureCount(total = 17, nextIndex = 2, isToday = true, showDeparted = false))
        assertEquals(0, foldedDepartureCount(total = 17, nextIndex = 14, isToday = true, showDeparted = true))
        assertEquals(0, foldedDepartureCount(total = 17, nextIndex = -1, isToday = false, showDeparted = false))
    }
}
