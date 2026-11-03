package cr.micampus.app.platform.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizeTierTest {
    @Test
    fun `responsive supported sizes select the matching tier`() {
        assertEquals(WidgetSizeTier.SQUARE, widgetSizeTier(DpSize(110.dp, 110.dp)))
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(110.dp, 180.dp)))
        assertEquals(WidgetSizeTier.COMPACT, widgetSizeTier(DpSize(180.dp, 110.dp)))
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(180.dp, 180.dp)))
        assertEquals(WidgetSizeTier.COMPACT, widgetSizeTier(DpSize(250.dp, 110.dp)))
        assertEquals(WidgetSizeTier.MEDIUM, widgetSizeTier(DpSize(250.dp, 180.dp)))
        assertEquals(WidgetSizeTier.TALL, widgetSizeTier(DpSize(250.dp, 280.dp)))
    }

    @Test
    fun `intermediate sizes keep the lower supported tier`() {
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(179.dp, 280.dp)))
        assertEquals(WidgetSizeTier.COMPACT, widgetSizeTier(DpSize(250.dp, 179.dp)))
        assertEquals(WidgetSizeTier.NARROW, widgetSizeTier(DpSize(249.dp, 280.dp)))
        assertEquals(WidgetSizeTier.MEDIUM, widgetSizeTier(DpSize(250.dp, 279.dp)))
    }

    @Test
    fun `only medium and tall show a header and list`() {
        assertFalse(WidgetSizeTier.SQUARE.showsList)
        assertFalse(WidgetSizeTier.NARROW.showsList)
        assertFalse(WidgetSizeTier.COMPACT.showsList)
        assertTrue(WidgetSizeTier.MEDIUM.showsList)
        assertTrue(WidgetSizeTier.TALL.showsList)
    }
}
