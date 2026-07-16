package cr.micampus.app.platform.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cr.micampus.app.MainActivity
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.data.institution.upcomingDepartures
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Preferences key storing the per-widget institution filter: "UCR" | "UNA" | "BOTH". */
val BUS_WIDGET_INSTITUTIONS_KEY: Preferences.Key<String> = stringPreferencesKey("institutions")
const val BUS_WIDGET_FILTER_UCR = "UCR"
const val BUS_WIDGET_FILTER_UNA = "UNA"
const val BUS_WIDGET_FILTER_BOTH = "BOTH"

private val busTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun institutionsFor(filter: String): List<Institution> = when (filter) {
    BUS_WIDGET_FILTER_UCR -> listOf(Institution.UCR)
    BUS_WIDGET_FILTER_UNA -> listOf(Institution.UNA)
    else -> listOf(Institution.UCR, Institution.UNA)
}

private fun labelFor(context: Context, filter: String): String = when (filter) {
    BUS_WIDGET_FILTER_UCR -> context.getString(R.string.widget_institution_ucr)
    BUS_WIDGET_FILTER_UNA -> context.getString(R.string.widget_institution_una)
    else -> context.getString(R.string.widget_institution_both)
}

class BusesWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val title = context.getString(R.string.widget_buses_label)
        val emptyLabel = context.getString(R.string.widget_buses_empty)

        provideContent {
            val prefs = currentState<Preferences>()
            val filter = prefs[BUS_WIDGET_INSTITUTIONS_KEY] ?: BUS_WIDGET_FILTER_BOTH
            val filterLabel = labelFor(context, filter)
            val departures = runCatching {
                app.container.transport.upcomingDepartures(
                    institutions = institutionsFor(filter),
                    now = LocalDateTime.now(),
                    limit = 4,
                )
            }.getOrDefault(emptyList())

            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>())
                        .semantics { contentDescription = "$title, filtro $filterLabel" },
                ) {
                    Text(
                        text = "$title · $filterLabel",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                    )
                    if (departures.isEmpty()) {
                        Text(
                            text = emptyLabel,
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.padding(top = 8.dp),
                        )
                    } else {
                        departures.forEach { departure -> DepartureRow(departure) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(departure: UpcomingDeparture) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = "${departure.institution.name} · ${departure.from} → ${departure.to}",
            maxLines = 1,
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
        Text(
            text = departure.departure.toLocalTime().format(busTimeFormatter),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

class BusesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BusesWidget()
}
