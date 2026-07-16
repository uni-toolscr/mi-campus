package cr.micampus.app.platform.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Central entry point to refresh both home-screen widgets after data changes
 * (event confirmation, transport updates, settings changes, etc).
 */
class WidgetRefresher(private val context: Context) {
    suspend fun refreshAll() {
        runCatching { EventsWidget().updateAll(context) }
        runCatching { BusesWidget().updateAll(context) }
    }
}
