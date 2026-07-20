package cr.micampus.app.platform.calendar

import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.time.LocalDateTime

class CalendarPermissionTest {
    @Test fun exportWithoutRuntimePermissionIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exporter = CalendarExporter(context)
        assumeFalse(exporter.hasPermission())
        val start = LocalDateTime.of(2026, 8, 3, 8, 0)
        val event = CampusEvent("permission", "Clase", Institution.UCR, EventKind.CLASS, start, start.plusHours(1))

        assertEquals(ExportResult.PermissionRequired, exporter.export(event, 1L, null))
    }
}
