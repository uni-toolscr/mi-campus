package cr.micampus.app.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.data.institution.upcomingDepartures
import java.time.LocalDateTime

class BusesWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(WIDGET_RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val title = context.getString(R.string.widget_buses_label)
        val emptyLabel = context.getString(R.string.widget_buses_empty)
        val use12h = runCatching { app.container.settings.current().use12hClock }.getOrDefault(false)

        provideContent {
            val prefs = currentState<Preferences>()
            val filter = prefs[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val directionIds = parseDirectionFilter(prefs[BUS_WIDGET_DIRECTIONS_KEY])
            val filterLabel = widgetInstitutionLabel(context, filter)
            val now = LocalDateTime.now()
            val tier = widgetSizeTier(LocalSize.current)
            val departures = runCatching {
                app.container.transport.upcomingDepartures(
                    institutions = institutionsFor(filter),
                    now = now,
                    limit = tier.itemLimit,
                    directionIds = directionIds,
                    departureGrace = busWidgetDepartureGrace,
                )
            }.getOrDefault(emptyList())

            GlanceTheme {
                WidgetShell(
                    title = "$title · $filterLabel",
                    iconRes = R.drawable.ic_widget_bus,
                    description = "$title, filtro $filterLabel",
                    launchDestination = WIDGET_DEST_TRANSPORT,
                    tier = tier,
                ) {
                    if (departures.isEmpty()) {
                        WidgetEmpty(emptyLabel, tier)
                    } else {
                        WidgetCardGroup(tier) {
                            val next = departures.first()
                            val presentation = heroItemPresentation(tier, HeroItemContent.BUS)
                            val countdown = busCountdownPresentation(next.departure, now)
                            val secondary = when (countdown) {
                                BusCountdownPresentation.Scheduled, null -> context.getString(R.string.widget_next_departure_scheduled)
                                BusCountdownPresentation.DepartingNow -> context.getString(R.string.widget_departing_now)
                                is BusCountdownPresentation.Minutes -> context.getString(
                                    if (presentation.secondaryShortLabel) {
                                        R.string.widget_next_departure_in_short
                                    } else {
                                        R.string.widget_next_departure_in
                                    },
                                    countdown.value,
                                )
                            }
                            HeroItem(
                                headline = next.departure.toLocalTime().format(timeFormatter(use12h)),
                                primary = routeText(next, filter),
                                secondary = secondary,
                                heroIconRes = R.drawable.ic_widget_bus,
                                tier = tier,
                                content = HeroItemContent.BUS,
                            )
                            departures.drop(1).forEach { departure ->
                                WidgetCardSeam()
                                FollowupRow(
                                    leading = departure.departure.toLocalTime().format(timeFormatter(use12h)),
                                    trailing = routeText(departure, filter),
                                    tier = tier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun routeText(departure: UpcomingDeparture, filter: String): String {
    val route = "${departure.from} → ${departure.to}"
    return if (filter == WIDGET_FILTER_BOTH) "${departure.institution.name} · $route" else route
}

class BusesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BusesWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Glance consumes this receiver's PendingResult in super.onUpdate(). A second
        // goAsync() here returns null and crashes the process, aborting every widget update.
        scheduleBusWidgetSynchronization(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        scheduleBusWidgetSynchronization(context)
    }
}
