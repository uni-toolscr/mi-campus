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
import cr.micampus.app.core.designsystem.widgetDateTimeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.EventRepository
import java.time.Instant

/** Home-screen widget mirroring the Agenda: events hidden by the agenda filters stay hidden here. */
class EventsWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(WIDGET_RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val entities = runCatching { app.container.events.futureEntities(Instant.now()) }.getOrDefault(emptyList())
        val settings = runCatching { app.container.settings.current() }.getOrNull()
        val use12h = settings?.use12hClock ?: false
        val allEvents = agendaVisibleEvents(
            entities.map { EventRepository.toDomain(it) },
            excludedKinds = settings?.agendaExcludedKinds ?: setOf(EventKind.CLASS),
            excludedInstitutions = settings?.agendaExcludedInstitutions ?: emptySet(),
        )
        val title = context.getString(R.string.widget_events_label)
        val emptyLabel = context.getString(R.string.widget_events_empty)

        provideContent {
            val prefs = currentState<Preferences>()
            val filter = prefs[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val institutions = institutionsFor(filter).toSet()
            val tier = widgetSizeTier(LocalSize.current)
            val events = allEvents.filter { it.institution in institutions }
                .take(tier.itemLimit)

            GlanceTheme {
                WidgetShell(
                    title = title,
                    iconRes = R.drawable.ic_widget_event,
                    description = title,
                    launchDestination = WIDGET_DEST_CALENDAR_AGENDA,
                    tier = tier,
                ) {
                    if (events.isEmpty()) {
                        WidgetEmpty(emptyLabel, tier)
                    } else {
                        WidgetCardGroup(tier) {
                            val next = events.first()
                            HeroItem(
                                headline = next.title,
                                primary = next.start.format(widgetDateTimeFormatter(use12h)),
                                secondary = next.institution.name,
                                heroIconRes = R.drawable.ic_widget_event,
                                tier = tier,
                                content = HeroItemContent.EVENT,
                            )
                            events.drop(1).forEach { event ->
                                WidgetCardSeam()
                                FollowupRow(leading = event.title, trailing = eventTrailing(event, use12h), tier = tier)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Same exclusion semantics as the Agenda filter in CalendarViewModel. */
fun agendaVisibleEvents(
    events: List<CampusEvent>,
    excludedKinds: Set<EventKind>,
    excludedInstitutions: Set<Institution>,
): List<CampusEvent> =
    events.filter { it.kind !in excludedKinds && it.institution !in excludedInstitutions }

private fun eventTrailing(event: CampusEvent, use12h: Boolean): String =
    "${event.start.format(widgetDateTimeFormatter(use12h))} · ${event.institution.name}"

class EventsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EventsWidget()
}
