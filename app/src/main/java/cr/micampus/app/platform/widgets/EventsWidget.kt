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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
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
            val size = LocalSize.current
            GlanceTheme {
                EventsWidgetContent(visible, now.toLocalDate(), courses, use12h, widgetSizeTier(size), widgetCardShowsDetail(size), description, emptyLabel)
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

/** The 2×2 card's detail line: the time (with its day unless it's today) and the course code or place. */
fun eventCardDetail(event: CampusEvent, courseCode: String?, today: LocalDate, use12h: Boolean): String {
    val time = if (event.start.toLocalDate() == today) event.start.format(timeFormatter(use12h)) else widgetWhenLabel(event.start, today, use12h)
    return listOfNotNull(time, courseCode ?: event.location.takeIf(String::isNotBlank)).joinToString(" · ")
}

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
    showDetail: Boolean,
    description: String,
    emptyLabel: String,
) {
    val card = tier == WidgetSizeTier.SQUARE || tier == WidgetSizeTier.NARROW
    val padding = when {
        tier.showsList -> 12.dp
        card && events.isNotEmpty() -> if (showDetail) 8.dp else 6.dp
        else -> 14.dp
    }
    WidgetFrame(description, WIDGET_DEST_CALENDAR_AGENDA, padding = padding) {
        val next = events.firstOrNull()
        when {
            next == null -> WidgetEmpty(emptyLabel, R.drawable.ic_widget_event)
            card -> EventCard(next, events.getOrNull(1), today, courses, use12h, showDetail)
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

/**
 * 2×2 layout: a segmented group with the next event on its course color, and below it one row
 * naming the event after it. The minimum size keeps only the top segment.
 */
@Composable
private fun ColumnScope.EventCard(
    event: CampusEvent,
    following: CampusEvent?,
    today: LocalDate,
    courses: WidgetCourses,
    use12h: Boolean,
    showDetail: Boolean,
) {
    val colors = courses.colorsFor(event) ?: WidgetCourseColors(
        GlanceTheme.colors.primaryContainer,
        GlanceTheme.colors.onPrimaryContainer,
        GlanceTheme.colors.primary,
    )
    SegmentedGroup(GlanceModifier.defaultWeight()) {
        Column(
            GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .background(colors.container)
                .cornerRadius(6.dp)
                .padding(horizontal = if (showDetail) 12.dp else 10.dp, vertical = if (showDetail) 10.dp else 8.dp),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val pill = eventPill(event, today)
                // Urgent pills keep the error container; the rest invert the course color so they never blend in.
                if (pill.urgent) WidgetPill(pill.text, urgent = true) else StatusPill(pill.text, background = colors.onContainer, color = colors.container)
                Spacer(GlanceModifier.defaultWeight())
                Image(
                    provider = ImageProvider(eventIcon(event.kind)),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.onContainer),
                    modifier = GlanceModifier.size(16.dp),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = event.title,
                maxLines = 2,
                style = TextStyle(fontSize = if (showDetail) 16.sp else 14.sp, fontWeight = FontWeight.Bold, color = colors.onContainer),
            )
            if (showDetail) {
                Text(
                    text = eventCardDetail(event, courses.codeFor(event), today, use12h),
                    maxLines = 1,
                    style = TextStyle(fontSize = 12.sp, color = colors.onContainer),
                )
            }
        }
        if (showDetail) {
            SegmentGap()
            Segment(horizontalPadding = 12.dp, verticalPadding = 8.dp) {
                if (following == null) {
                    Text("Nada más pendiente", maxLines = 1, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                } else {
                    CourseDot(courses.colorsFor(following)?.accent ?: GlanceTheme.colors.outline)
                    Spacer(GlanceModifier.width(7.dp))
                    Text(
                        text = following.title,
                        maxLines = 1,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = widgetDayLabel(following.start.toLocalDate(), today),
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

class EventsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EventsWidget()
}
