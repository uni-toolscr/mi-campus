package cr.micampus.app.feature.contents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ContentsFormatsTest {
    private val zone = ZoneId.of("America/Costa_Rica")
    private val now = LocalDateTime.of(2026, 10, 28, 15, 0).epoch()

    @Test fun recentSyncsUseRelativeMinutesAndHours() {
        assertEquals("Sincronizado ahora", lastSyncLabel(now - 30_000, now, zone))
        assertEquals("Sincronizado ahora", lastSyncLabel(now + 60_000, now, zone))
        assertEquals("Sincronizado hace 5 min", lastSyncLabel(now - 5 * 60_000, now, zone))
        assertEquals("Sincronizado hace 2 h", lastSyncLabel(now - 2 * 3_600_000 - 59 * 60_000, now, zone))
    }

    @Test fun olderSyncsUseCalendarDays() {
        assertEquals("Sincronizado ayer", lastSyncLabel(LocalDateTime.of(2026, 10, 27, 9, 0).epoch(), now, zone))
        assertEquals("Sincronizado el 12 de octubre", lastSyncLabel(LocalDateTime.of(2026, 10, 12, 9, 0).epoch(), now, zone))
        assertEquals("Sincronizado el 3 de diciembre de 2025", lastSyncLabel(LocalDateTime.of(2025, 12, 3, 9, 0).epoch(), now, zone))
    }

    @Test fun downloadFractionIsDeterminateOnlyWithAKnownSize() {
        assertEquals(0.25f, downloadFraction(512, 2_048)!!, 0.0001f)
        assertEquals(1f, downloadFraction(4_096, 2_048)!!, 0.0001f)
        assertNull(downloadFraction(512, null))
        assertNull(downloadFraction(512, 0))
    }

    @Test fun downloadLabelsShowPercentOrBytes() {
        assertEquals("Descargando · 25%", downloadProgressLabel(512, 2_048))
        assertEquals("Descargando · 2 KB", downloadProgressLabel(2_048, null))
        assertEquals("900 B", formatBytes(900))
    }

    private fun LocalDateTime.epoch() = atZone(zone).toInstant().toEpochMilli()
}
