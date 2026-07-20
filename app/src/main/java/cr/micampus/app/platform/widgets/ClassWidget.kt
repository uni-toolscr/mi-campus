package cr.micampus.app.platform.widgets

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
import cr.micampus.app.core.designsystem.formatTimeRange
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.local.EventRepository
import java.time.Instant
import java.time.LocalDateTime

/**
 * Home-screen widget showing today's remaining confirmed classes (EventKind.CLASS).
 *
 * Staleness: content recomputes on data changes (WidgetRefresher) and on the periodic
 * update (widget_class_info.xml, every 30 min), so shortly after midnight the widget may
 * show the previous day's list for up to one period until the next refresh.
 */
class ClassWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(WIDGET_RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val entities = runCatching { app.container.events.futureEntities(Instant.now()) }.getOrDefault(emptyList())
        val classes = todaysClasses(
            entities.map { EventRepository.toDomain(it) }.filter { it.kind == EventKind.CLASS },
            LocalDateTime.now(),
        )
        val use12h = runCatching { app.container.settings.current().use12hClock }.getOrDefault(false)
        val title = context.getString(R.string.widget_class_label)
        val emptyLabel = context.getString(R.string.widget_class_empty)

        provideContent {
            val prefs = currentState<Preferences>()
            val filter = prefs[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val institutions = institutionsFor(filter).toSet()
            val tier = widgetSizeTier(LocalSize.current)
            val events = classes.filter { it.institution in institutions }
                .take(tier.itemLimit)

            GlanceTheme {
                WidgetShell(
                    title = title,
                    iconRes = R.drawable.ic_widget_class,
                    description = title,
                    launchDestination = WIDGET_DEST_CALENDAR_HORARIO,
                    tier = tier,
                ) {
                    if (events.isEmpty()) {
                        WidgetEmpty(emptyLabel, tier)
                    } else {
                        WidgetCardGroup(tier) {
                            val next = events.first()
                            HeroItem(
                                headline = next.title,
                                primary = formatTimeRange(next.start, next.end, use12h),
                                secondary = next.location.ifBlank { next.institution.name },
                                heroIconRes = R.drawable.ic_widget_class,
                                tier = tier,
                                content = HeroItemContent.EVENT,
                            )
                            events.drop(1).forEach { event ->
                                WidgetCardSeam()
                                FollowupRow(leading = event.title, trailing = classTrailing(event, use12h), tier = tier)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Classes that start today and have not ended yet (in-progress classes remain visible). */
fun todaysClasses(events: List<CampusEvent>, now: LocalDateTime): List<CampusEvent> =
    events.filter { it.start.toLocalDate() == now.toLocalDate() && it.end >= now }

private fun classTrailing(event: CampusEvent, use12h: Boolean): String =
    formatTimeRange(event.start, event.end, use12h)

class ClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClassWidget()
}
