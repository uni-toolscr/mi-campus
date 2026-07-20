package cr.micampus.app.core.designsystem

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatsTest {
    private val morningStart = LocalDateTime.of(2026, 7, 20, 9, 0)
    private val morningEnd = LocalDateTime.of(2026, 7, 20, 10, 50)
    private val noonCrossStart = LocalDateTime.of(2026, 7, 20, 11, 30)
    private val afternoonEnd = LocalDateTime.of(2026, 7, 20, 13, 0)

    @Test
    fun `24h range uses plain times`() {
        assertEquals("13:00 – 14:50", formatTimeRange(afternoonEnd, LocalDateTime.of(2026, 7, 20, 14, 50), use12h = false))
    }

    @Test
    fun `12h range collapses shared meridiem`() {
        val result = formatTimeRange(morningStart, morningEnd, use12h = true)
        val meridiem = morningEnd.format(timeFormatter(true)).substringAfter("10:50").trim()
        assertEquals("9:00 – 10:50 $meridiem", result)
    }

    @Test
    fun `12h range spanning midnight start keeps collapse within same meridiem`() {
        val midnight = LocalDateTime.of(2026, 7, 20, 0, 0)
        val early = LocalDateTime.of(2026, 7, 20, 1, 30)
        val formatter = timeFormatter(true)
        val meridiem = early.format(formatter).substringAfter("1:30").trim()
        assertEquals("12:00 – 1:30 $meridiem", formatTimeRange(midnight, early, use12h = true))
    }

    @Test
    fun `12h range ending exactly at noon keeps both meridiems`() {
        val late = LocalDateTime.of(2026, 7, 20, 11, 0)
        val noon = LocalDateTime.of(2026, 7, 20, 12, 0)
        val formatter = timeFormatter(true)
        val expected = "${late.format(formatter)} – ${noon.format(formatter)}"
        assertEquals(expected, formatTimeRange(late, noon, use12h = true))
    }

    @Test
    fun `12h range keeps both meridiems across noon`() {
        val formatter = timeFormatter(true)
        val expected = "${noonCrossStart.format(formatter)} – ${afternoonEnd.format(formatter)}"
        assertEquals(expected, formatTimeRange(noonCrossStart, afternoonEnd, use12h = true))
    }
}
