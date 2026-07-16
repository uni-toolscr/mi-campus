package cr.micampus.app.platform.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cr.micampus.app.MainActivity
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.data.local.EventRepository
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val eventDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag("es-CR"))

class EventsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val entities = runCatching { app.container.events.futureEntities(Instant.now()) }.getOrDefault(emptyList())
        val events = entities.take(5).map { EventRepository.toDomain(it) }
        val title = context.getString(R.string.widget_events_label)
        val emptyLabel = context.getString(R.string.widget_events_empty)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>())
                        .semantics { contentDescription = title },
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                    )
                    if (events.isEmpty()) {
                        Text(
                            text = emptyLabel,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.padding(top = 8.dp),
                        )
                    } else {
                        events.forEach { event -> EventRow(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CampusEvent) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = event.title,
            maxLines = 1,
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
        Text(
            text = "${event.start.format(eventDateFormatter)} · ${event.institution.name}",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

class EventsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EventsWidget()
}
