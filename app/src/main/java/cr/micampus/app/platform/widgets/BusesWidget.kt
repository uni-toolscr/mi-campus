package cr.micampus.app.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
        val description = context.getString(R.string.widget_buses_label)
        val emptyLabel = context.getString(R.string.widget_buses_empty)
        val use12h = runCatching { app.container.settings.current().use12hClock }.getOrDefault(false)

        provideContent {
            val prefs = currentState<Preferences>()
            val filter = prefs[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val directionIds = parseDirectionFilter(prefs[BUS_WIDGET_DIRECTIONS_KEY])
            val now = LocalDateTime.now()
            val tier = widgetSizeTier(LocalSize.current)
            val departures = runCatching {
                app.container.transport.upcomingDepartures(
                    institutions = institutionsFor(filter),
                    now = now,
                    limit = BUS_DEPARTURE_LIMIT,
                    directionIds = directionIds,
                    departureGrace = busWidgetDepartureGrace,
                )
            }.getOrDefault(emptyList())

            GlanceTheme {
                BusesWidgetContent(departures, now, use12h, filter, tier, "$description, ${widgetInstitutionLabel(context, filter)}", emptyLabel)
            }
        }
    }
}

private const val BUS_DEPARTURE_LIMIT = 5

/** "Heredia → UCR"; the institution is added only when the widget mixes both and neither endpoint names it. */
fun busRouteLabel(departure: UpcomingDeparture, filter: String): String {
    val route = "${departure.from} → ${departure.to}"
    val institution = departure.institution.name
    val named = departure.from.contains(institution) || departure.to.contains(institution)
    return if (filter == WIDGET_FILTER_BOTH && !named) "$institution · $route" else route
}

/** The hero's countdown line: "en 23 min" inside the live window, "Saliendo" at departure, else a plain label. */
fun busCountdownLabel(departure: LocalDateTime, now: LocalDateTime): String =
    when (val countdown = busCountdownPresentation(departure, now)) {
        is BusCountdownPresentation.Minutes -> "en ${countdown.value} min"
        BusCountdownPresentation.DepartingNow -> "Saliendo"
        BusCountdownPresentation.Scheduled, null -> "Próxima salida"
    }

private fun busRowLimit(tier: WidgetSizeTier): Int = if (tier == WidgetSizeTier.TALL) 4 else 3

@Composable
private fun BusesWidgetContent(
    departures: List<UpcomingDeparture>,
    now: LocalDateTime,
    use12h: Boolean,
    filter: String,
    tier: WidgetSizeTier,
    description: String,
    emptyLabel: String,
) {
    val next = departures.firstOrNull()
    if (next == null) {
        WidgetFrame(description, WIDGET_DEST_TRANSPORT) { WidgetEmpty(emptyLabel, R.drawable.ic_widget_bus) }
        return
    }
    val time = badgeClock(next.departure, use12h)
    val countdown = busCountdownLabel(next.departure, now)
    val route = busRouteLabel(next, filter)
    when (tier) {
        WidgetSizeTier.SQUARE, WidgetSizeTier.NARROW ->
            WidgetFrame(description, WIDGET_DEST_TRANSPORT, background = GlanceTheme.colors.tertiaryContainer) {
                BusRouteLine(route, GlanceTheme.colors.onTertiaryContainer)
                Column(GlanceModifier.fillMaxWidth().defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    DepartureShape(R.drawable.widget_shape_sunny_soft, if (tier == WidgetSizeTier.SQUARE) 84.dp else 112.dp, time, countdown)
                }
                val later = departures.drop(1).take(2)
                if (later.isNotEmpty()) {
                    Text(
                        text = "Luego " + later.joinToString(" · ") { it.departure.format(timeFormatter(use12h)) },
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onTertiaryContainer),
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        WidgetSizeTier.COMPACT ->
            WidgetFrame(description, WIDGET_DEST_TRANSPORT, background = GlanceTheme.colors.tertiaryContainer) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    DepartureShape(R.drawable.widget_shape_sunny_soft, 80.dp, time, countdown)
                    Spacer(GlanceModifier.width(12.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        BusRouteLine(route, GlanceTheme.colors.onTertiaryContainer)
                        departures.getOrNull(1)?.let { later ->
                            Text(
                                text = "Luego ${later.departure.format(timeFormatter(use12h))}",
                                maxLines = 1,
                                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onTertiaryContainer),
                            )
                        }
                    }
                }
            }
        WidgetSizeTier.MEDIUM, WidgetSizeTier.TALL ->
            WidgetFrame(description, WIDGET_DEST_TRANSPORT) {
                WidgetHeader(route, iconRes = R.drawable.ic_widget_bus)
                Row(GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(20.dp)) {
                    Column(
                        modifier = GlanceModifier.width(150.dp).fillMaxHeight().background(GlanceTheme.colors.tertiaryContainer).cornerRadius(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DepartureShape(R.drawable.widget_shape_cookie_soft, 110.dp, time, countdown)
                    }
                    Spacer(GlanceModifier.width(2.dp))
                    Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                        val later = departures.drop(1).take(busRowLimit(tier))
                        later.forEachIndexed { index, departure ->
                            if (index > 0) SegmentGap()
                            val laterRoute = busRouteLabel(departure, filter)
                            Segment(GlanceModifier.defaultWeight()) {
                                Text(
                                    text = departure.departure.format(timeFormatter(use12h)),
                                    maxLines = 1,
                                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                                )
                                Spacer(GlanceModifier.width(8.dp))
                                // Only routes that differ from the header's are spelled out; the time always keeps its room.
                                Text(
                                    text = if (laterRoute != route) laterRoute else "",
                                    maxLines = 2,
                                    style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant, textAlign = TextAlign.End),
                                    modifier = GlanceModifier.defaultWeight(),
                                )
                            }
                        }
                        if (later.isEmpty()) {
                            Segment(GlanceModifier.defaultWeight()) {
                                Text("Última salida de hoy", maxLines = 2, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun ColumnScope.BusRouteLine(route: String, color: ColorProvider) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_bus),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = GlanceModifier.size(14.dp),
        )
        Spacer(GlanceModifier.width(5.dp))
        Text(route, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color))
    }
    Spacer(GlanceModifier.height(2.dp))
}

@Composable
private fun DepartureShape(shapeRes: Int, size: Dp, time: BadgeClock, countdown: String) {
    ShapeBadge(
        shapeRes = shapeRes,
        size = size,
        shapeColor = GlanceTheme.colors.onTertiaryContainer,
        textColor = GlanceTheme.colors.onTertiaryContainer,
        text = ShapeBadgeText(time.meridiem, time.time, countdown),
        valueSize = if (size < 100.dp) 22.sp else 28.sp,
    )
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
