package cr.micampus.app.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cr.micampus.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class WidgetMetadataApi31Test {
    @Test
    fun providersExposeFourByTwoResponsivePreviewMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)

        assertMetadata(manager, context, ClassWidgetReceiver::class.java, R.layout.widget_class_preview)
        assertMetadata(manager, context, EventsWidgetReceiver::class.java, R.layout.widget_events_preview)
        assertMetadata(manager, context, BusesWidgetReceiver::class.java, R.layout.widget_buses_preview)
    }

    private fun assertMetadata(
        manager: AppWidgetManager,
        context: android.content.Context,
        receiver: Class<*>,
        previewLayout: Int,
    ) {
        val info = manager.installedProviders.firstOrNull {
            it.provider == ComponentName(context, receiver)
        }
        assertNotNull("Provider metadata missing for ${receiver.simpleName}", info)
        requireNotNull(info).also {
            assertEquals(4, it.targetCellWidth)
            assertEquals(2, it.targetCellHeight)
            assertEquals(previewLayout, it.previewLayout)
            val expectedMinimumResize = (110 * context.resources.displayMetrics.density).roundToInt()
            assertEquals(expectedMinimumResize, it.minResizeWidth)
            assertEquals(expectedMinimumResize, it.minResizeHeight)
        }
    }
}
