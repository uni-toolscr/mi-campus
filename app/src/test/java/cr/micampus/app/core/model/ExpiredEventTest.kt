package cr.micampus.app.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ExpiredEventTest {
    private val now = LocalDateTime.of(2026, 7, 22, 12, 0)

    @Test fun endBeforeNowIsExpired() {
        assertTrue(eventEndingAt(now.minusSeconds(1)).isExpired(now))
    }

    @Test fun endExactlyAtNowIsNotExpired() {
        assertFalse(eventEndingAt(now).isExpired(now))
    }

    @Test fun endAfterNowIsNotExpired() {
        assertFalse(eventEndingAt(now.plusHours(1)).isExpired(now))
    }

    private fun eventEndingAt(end: LocalDateTime) = CampusEvent(
        id = "e",
        title = "Examen",
        institution = Institution.UCR,
        kind = EventKind.EXAM,
        start = end.minusHours(1),
        end = end,
    )
}
