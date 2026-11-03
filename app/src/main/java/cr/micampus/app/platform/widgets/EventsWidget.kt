package cr.micampus.app.platform.widgets

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.feature.home.HomeEventBadge
import cr.micampus.app.feature.home.homeEventBadge
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Home-screen widget mirroring the Agenda: events hidden by the agenda filters stay hidden here. */
class EventsWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(WIDGET_RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val events = runCatching { app.container.events.allEntities().map { EventRepository.toDomain(it) } }.getOrDefault(emptyList())
        val settings = runCatching { app.container.settings.current() }.getOrNull()
        val use12h = settings?.use12hClock ?: false
        val upcoming = agendaVisibleEvents(
            events.filter { !it.end.isBefore(now) && !it.completed }.sortedBy(CampusEvent::start),
            excludedKinds = settings?.agendaExcludedKinds ?: setOf(EventKind.CLASS),
            excludedInstitutions = settings?.agendaExcludedInstitutions ?: emptySet(),
        )
        val courses = loadWidgetCourses(app, events, now)
        val description = context.getString(R.string.widget_events_label)
        val emptyLabel = context.getString(R.string.widget_events_empty)

        provideContent {
            val filter = currentState<Preferences>()[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val institutions = institutionsFor(filter).toSet()
            val visible = upcoming.filter { it.institution in institutions }
            val tier = widgetSizeTier(LocalSize.current)
            GlanceTheme {
                EventsWidgetContent(visible, now.toLocalDate(), courses, use12h, tier, description, emptyLabel)
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

/** A row's pill: Inicio's badge when it has one (Hoy, Mañana, Examen, Quiz), else how many days away. */
data class EventPill(val text: String, val urgent: Boolean)

fun eventPill(event: CampusEvent, today: LocalDate): EventPill = when (homeEventBadge(event, today)) {
    HomeEventBadge.TODAY -> EventPill("Hoy", urgent = true)
    HomeEventBadge.TOMORROW -> EventPill("Mañana", urgent = true)
    HomeEventBadge.EXAM -> EventPill("Examen", urgent = false)
    HomeEventBadge.QUIZ -> EventPill("Quiz", urgent = false)
    null -> EventPill(widgetDaysAwayLabel(event.start.toLocalDate(), today), urgent = false)
}

fun eventSupporting(event: CampusEvent, today: LocalDate, use12h: Boolean): String =
    listOfNotNull(widgetWhenLabel(event.start, today, use12h), event.location.takeIf(String::isNotBlank)).joinToString(" · ")

fun pendingEventsLabel(count: Int): String = if (count == 1) "1 pendiente" else "$count pendientes"

private fun eventRowLimit(tier: WidgetSizeTier): Int = when (tier) {
    WidgetSizeTier.MEDIUM -> 3
    WidgetSizeTier.TALL -> 5
    else -> 1
}

private fun eventIcon(kind: EventKind): Int = when (kind) {
    EventKind.TAREA -> R.drawable.ic_widget_task
    EventKind.EXAM, EventKind.QUIZ -> R.drawable.ic_widget_quiz
    else -> R.drawable.ic_widget_event
}

@Composable
private fun EventsWidgetContent(
    events: List<CampusEvent>,
    today: LocalDate,
    courses: WidgetCourses,
    use12h: Boolean,
    tier: WidgetSizeTier,
    description: String,
    emptyLabel: String,
) {
    WidgetFrame(description, WIDGET_DEST_CALENDAR_AGENDA, padding = if (tier.showsList) 12.dp else 14.dp) {
        val next = events.firstOrNull()
        when {
            next == null -> WidgetEmpty(emptyLabel, R.drawable.ic_widget_event)
            tier == WidgetSizeTier.SQUARE || tier == WidgetSizeTier.NARROW -> NextEventCard(next, events.drop(1), today, courses, use12h)
            else -> {
                if (tier.showsList) WidgetHeader("Próximos eventos", pendingEventsLabel(events.size))
                val rows = events.take(eventRowLimit(tier))
                SegmentedGroup(if (tier == WidgetSizeTier.COMPACT) GlanceModifier.defaultWeight() else GlanceModifier) {
                    rows.forEachIndexed { index, event ->
                        if (index > 0) SegmentGap()
                        val modifier = if (tier == WidgetSizeTier.COMPACT) GlanceModifier.fillMaxSize() else GlanceModifier
                        Segment(modifier, verticalPadding = 7.dp) {
                            CourseBar(courses.colorsFor(event)?.accent ?: GlanceTheme.colors.outline)
                            Spacer(GlanceModifier.width(10.dp))
                            RowText(event.title, eventSupporting(event, today, use12h))
                            Spacer(GlanceModifier.width(8.dp))
                            eventPill(event, today).let { WidgetPill(it.text, it.urgent) }
                        }
                    }
                }
            }
        }
    }
}

/** 2×2 layout: the next event large, with dots for the ones after it. */
@Composable
private fun ColumnScope.NextEventCard(
    event: CampusEvent,
    later: List<CampusEvent>,
    today: LocalDate,
    courses: WidgetCourses,
    use12h: Boolean,
) {
    val accent = courses.colorsFor(event)?.accent ?: GlanceTheme.colors.primary
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        eventPill(event, today).let { WidgetPill(it.text, it.urgent) }
        Spacer(GlanceModifier.defaultWeight())
        Image(
            provider = ImageProvider(eventIcon(event.kind)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(accent),
            modifier = GlanceModifier.size(18.dp),
        )
    }
    Spacer(GlanceModifier.height(6.dp))
    Text(
        text = event.title,
        maxLines = 2,
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
    )
    Text(
        text = listOfNotNull(event.start.format(timeFormatter(use12h)), courses.codeFor(event) ?: event.location.takeIf(String::isNotBlank)).joinToString(" · "),
        maxLines = 1,
        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
    )
    Spacer(GlanceModifier.defaultWeight())
    if (later.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            later.take(3).forEach { event ->
                CourseDot(courses.colorsFor(event)?.accent ?: GlanceTheme.colors.outline)
                Spacer(GlanceModifier.width(4.dp))
            }
            Spacer(GlanceModifier.width(2.dp))
            Text(
                text = "+${later.size} ${if (later.size == 1) "próximo" else "próximos"}",
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

class EventsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EventsWidget()
}
