package cr.micampus.app.platform.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels drawn by the 2×2 cards of the class, events, and bus widgets. */
class WidgetCardLabelsTest {
    private val now = LocalDateTime.of(2026, 7, 20, 10, 35) // Monday
    private val today = now.toLocalDate()

    private fun event(start: LocalDateTime, end: LocalDateTime, kind: EventKind = EventKind.CLASS, location: String = "") = CampusEvent(
        id = "e",
        title = "Cálculo I",
        institution = Institution.UCR,
        kind = kind,
        start = start,
        end = end,
        location = location,
    )

    @Test
    fun `only the 110 dp minimum hides the card details`() {
        assertFalse(widgetCardShowsDetail(DpSize(110.dp, 110.dp)))
        assertTrue(widgetCardShowsDetail(DpSize(150.dp, 150.dp)))
        assertTrue(widgetCardShowsDetail(DpSize(110.dp, 180.dp)))
        assertEquals(WidgetSizeTier.SQUARE, widgetSizeTier(DpSize(150.dp, 150.dp)))
    }

    @Test
    fun `class in progress counts down the minutes left and reports progress`() {
        val running = event(now.minusMinutes(85), now.plusMinutes(25))
        val state = classHeroState(running, now)

        assertEquals("Ahora", classCardStatus(state, running.start.toLocalDate(), today))
        assertEquals(WidgetCountdown("quedan", "25", "min"), classCardCountdown(running, state, use12h = false))
        assertEquals(85f / 110f, classProgress(running, now)!!, 0.001f)
    }

    @Test
    fun `class starting soon counts up to its start and has no progress`() {
        val soon = event(now.plusMinutes(18), now.plusMinutes(128))
        val state = classHeroState(soon, now)

        assertEquals("Hoy", classCardStatus(state, soon.start.toLocalDate(), today))
        assertEquals(WidgetCountdown("empieza en", "18", "min"), classCardCountdown(soon, state, use12h = false))
        assertNull(classProgress(soon, now))
    }

    @Test
    fun `class outside the countdown window shows its start time and day`() {
        val tomorrow = event(LocalDateTime.of(2026, 7, 21, 8, 0), LocalDateTime.of(2026, 7, 21, 9, 50))
        val state = classHeroState(tomorrow, now)

        assertEquals("Mañana", classCardStatus(state, tomorrow.start.toLocalDate(), today))
        assertEquals(WidgetCountdown("empieza", "08:00", null), classCardCountdown(tomorrow, state, use12h = false))
    }

    @Test
    fun `long class outside the window shows when it ends`() {
        val long = event(now.minusMinutes(10), now.plusHours(2))
        assertEquals(WidgetCountdown("hasta", "12:35", null), classCardCountdown(long, classHeroState(long, now), use12h = false))
    }

    @Test
    fun `event detail drops the day for today and prefers the course code`() {
        val tonight = event(now.withHour(23).withMinute(59), now.withHour(23).withMinute(59), EventKind.TAREA, location = "Aula 204")
        assertEquals("23:59 · IF-2000", eventCardDetail(tonight, "IF-2000", today, use12h = false))
        assertEquals("23:59 · Aula 204", eventCardDetail(tonight, null, today, use12h = false))
    }

    @Test
    fun `bus card leads with minutes inside the live window and the time outside it`() {
        val soon = now.plusMinutes(12)
        assertEquals(
            BusCardHeadline(WidgetCountdown("sale en", "12", "min"), "10:47"),
            busCardHeadline(soon, now, use12h = false),
        )
        val later = now.plusHours(2)
        assertEquals(
            BusCardHeadline(WidgetCountdown("próxima salida", "12:35", null), null),
            busCardHeadline(later, now, use12h = false),
        )
        assertEquals(
            BusCardHeadline(WidgetCountdown("saliendo ahora", "10:35", null), null),
            busCardHeadline(now, now, use12h = false),
        )
    }
}
