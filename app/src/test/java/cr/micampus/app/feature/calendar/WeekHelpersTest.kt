package cr.micampus.app.feature.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class WeekHelpersTest {
    @Test fun mondayOfSnapsBackToMondayWithinTheSameWeek() {
        // 2026-07-21 is a Tuesday; its week's Monday is 2026-07-20.
        assertEquals(LocalDate.of(2026, 7, 20), mondayOf(LocalDate.of(2026, 7, 21)))
    }

    @Test fun mondayOfIsIdempotentOnAMonday() {
        val monday = LocalDate.of(2026, 7, 20)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(monday, mondayOf(monday))
    }

    @Test fun mondayOfHandlesSundayAsEndOfWeek() {
        // 2026-07-26 is a Sunday; it still belongs to the week starting 2026-07-20.
        assertEquals(LocalDate.of(2026, 7, 20), mondayOf(LocalDate.of(2026, 7, 26)))
    }

    @Test fun weekDaysReturnsSevenConsecutiveDaysMondayToSunday() {
        val days = weekDays(LocalDate.of(2026, 7, 20))
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 7, 20), days.first())
        assertEquals(LocalDate.of(2026, 7, 26), days.last())
        assertEquals(DayOfWeek.MONDAY, days.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, days.last().dayOfWeek)
    }
}
