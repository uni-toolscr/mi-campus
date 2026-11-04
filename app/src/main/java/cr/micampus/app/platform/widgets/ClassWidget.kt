package cr.micampus.app.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.formatTimeRange
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.local.EventRepository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Home-screen widget led by the class in progress (or the next one), with a countdown on an
 * expressive shape and the following classes below. The countdown stays live through the widget
 * alarm in [BusWidgetRefreshCoordinator], which refreshes every minute in the last half hour
 * before a class starts or ends.
 */
class ClassWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(WIDGET_RESPONSIVE_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as MiCampusApplication
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val events = runCatching { app.container.events.allEntities().map { EventRepository.toDomain(it) } }.getOrDefault(emptyList())
        val courses = loadWidgetCourses(app, events, now)
        val classes = upcomingClasses(events, now)
        val use12h = runCatching { app.container.settings.current().use12hClock }.getOrDefault(false)
        val description = context.getString(R.string.widget_class_label)
        val emptyLabel = context.getString(R.string.widget_class_empty)

        provideContent {
            val filter = currentState<Preferences>()[WIDGET_INSTITUTIONS_KEY] ?: WIDGET_FILTER_BOTH
            val institutions = institutionsFor(filter).toSet()
            val visible = classes.filter { it.institution in institutions }
            val size = LocalSize.current
            GlanceTheme {
                ClassWidgetContent(visible, now, courses, use12h, widgetSizeTier(size), widgetCardShowsDetail(size), description, emptyLabel)
            }
        }
    }
}

/** Confirmed classes that have not ended yet, soonest first; the first one leads the widget. */
fun upcomingClasses(events: List<CampusEvent>, now: LocalDateTime): List<CampusEvent> =
    events.filter { it.kind == EventKind.CLASS && it.end.isAfter(now) }.sortedBy(CampusEvent::start)

/** Where the leading class stands; minute counts are null outside the live countdown window. */
sealed interface ClassHeroState {
    data class InProgress(val minutesLeft: Int?) : ClassHeroState
    data class Today(val minutesUntil: Int?) : ClassHeroState
    data object Later : ClassHeroState
}

fun classHeroState(event: CampusEvent, now: LocalDateTime): ClassHeroState = when {
    !event.start.isAfter(now) -> ClassHeroState.InProgress(countdownMinutes(event.end, now))
    event.start.toLocalDate() == now.toLocalDate() -> ClassHeroState.Today(countdownMinutes(event.start, now))
    else -> ClassHeroState.Later
}

/** The next moment the leading class's countdown changes: its start if upcoming, else its end. */
fun classWidgetRefreshPlan(event: CampusEvent, now: LocalDateTime): BusWidgetRefreshPlan =
    busWidgetRefreshPlan(if (event.start.isAfter(now)) event.start else event.end, now, Duration.ZERO)

private fun countdownMinutes(target: LocalDateTime, now: LocalDateTime): Int? =
    (busCountdownPresentation(target, now, Duration.ZERO) as? BusCountdownPresentation.Minutes)?.value

fun classHeroOverline(state: ClassHeroState, courseCode: String?): String {
    val status = if (state is ClassHeroState.InProgress) "EN CURSO" else "PRÓXIMA CLASE"
    return listOfNotNull(status, courseCode).joinToString(" · ")
}

/** "13 min" while counting down; otherwise the clock time with what it refers to ("hasta 15:50", "mañana 08:00"). */
fun classHeroBadge(event: CampusEvent, state: ClassHeroState, today: LocalDate, use12h: Boolean): ShapeBadgeText {
    val start = badgeClock(event.start, use12h)
    return when (state) {
        is ClassHeroState.InProgress -> state.minutesLeft?.let { ShapeBadgeText(null, "$it", "min") }
            ?: badgeClock(event.end, use12h).let { ShapeBadgeText("hasta", it.time, it.meridiem) }
        is ClassHeroState.Today -> state.minutesUntil?.let { ShapeBadgeText("en", "$it", "min") }
            ?: ShapeBadgeText("hoy", start.time, start.meridiem)
        ClassHeroState.Later -> ShapeBadgeText(widgetDayLabel(event.start.toLocalDate(), today), start.time, start.meridiem)
    }
}

/** The 2×2 card's status pill: "Ahora" during the class, "Hoy" later today, else the day ("Mañana", "Mié", "9 nov"). */
fun classCardStatus(state: ClassHeroState, start: LocalDate, today: LocalDate): String = when (state) {
    is ClassHeroState.InProgress -> "Ahora"
    is ClassHeroState.Today -> "Hoy"
    ClassHeroState.Later -> widgetDayLabel(start, today).replaceFirstChar(Char::uppercaseChar)
}

/** The 2×2 card's headline: minutes while counting down ("quedan 25 min", "empieza en 18 min"), else a clock time. */
fun classCardCountdown(event: CampusEvent, state: ClassHeroState, use12h: Boolean): WidgetCountdown {
    val start = badgeClock(event.start, use12h)
    return when (state) {
        is ClassHeroState.InProgress -> state.minutesLeft?.let { WidgetCountdown("quedan", "$it", "min") }
            ?: badgeClock(event.end, use12h).let { WidgetCountdown("hasta", it.time, it.meridiem) }
        is ClassHeroState.Today -> state.minutesUntil?.let { WidgetCountdown("empieza en", "$it", "min") }
            ?: WidgetCountdown("empieza", start.time, start.meridiem)
        ClassHeroState.Later -> WidgetCountdown("empieza", start.time, start.meridiem)
    }
}

/** How far through the class [now] is, 0–1; null when the class hasn't started. */
fun classProgress(event: CampusEvent, now: LocalDateTime): Float? {
    if (event.start.isAfter(now)) return null
    val total = Duration.between(event.start, event.end).toMinutes()
    if (total <= 0) return 1f
    return (Duration.between(event.start, now).toMinutes().toFloat() / total).coerceIn(0f, 1f)
}

/** Trailing label of a following class: its start time today, else the relative day and time. */
fun classRowTrailing(event: CampusEvent, today: LocalDate, use12h: Boolean): String =
    if (event.start.toLocalDate() == today) event.start.format(timeFormatter(use12h)) else widgetWhenLabel(event.start, today, use12h)

fun remainingTodayLabel(classes: List<CampusEvent>, today: LocalDate): String =
    when (val count = classes.count { it.start.toLocalDate() == today }) {
        0 -> "Ninguna más hoy"
        1 -> "1 restante hoy"
        else -> "$count restantes hoy"
    }

private fun classRowLimit(tier: WidgetSizeTier): Int = when (tier) {
    WidgetSizeTier.MEDIUM -> 1
    WidgetSizeTier.TALL -> 3
    else -> 0
}

@Composable
private fun ClassWidgetContent(
    classes: List<CampusEvent>,
    now: LocalDateTime,
    courses: WidgetCourses,
    use12h: Boolean,
    tier: WidgetSizeTier,
    showDetail: Boolean,
    description: String,
    emptyLabel: String,
) {
    val hero = classes.firstOrNull()
    if (hero == null) {
        WidgetFrame(description, WIDGET_DEST_CALENDAR_HORARIO) { WidgetEmpty(emptyLabel, R.drawable.ic_widget_class) }
        return
    }
    val today = now.toLocalDate()
    val state = classHeroState(hero, now)
    val colors = courses.colorsFor(hero) ?: WidgetCourseColors(
        GlanceTheme.colors.primaryContainer,
        GlanceTheme.colors.onPrimaryContainer,
        GlanceTheme.colors.primary,
    )
    val heroContent = ClassHeroContent(
        title = hero.title,
        overline = classHeroOverline(state, courses.codeFor(hero)),
        detail = listOfNotNull(formatTimeRange(hero.start, hero.end, use12h), hero.location.takeIf(String::isNotBlank)).joinToString(" · "),
        badge = classHeroBadge(hero, state, today, use12h),
        colors = colors,
    )
    if (!tier.showsList) {
        // Small sizes: the hero card is the whole widget.
        val padding = if (tier == WidgetSizeTier.COMPACT || showDetail) 14.dp else 10.dp
        WidgetFrame(description, WIDGET_DEST_CALENDAR_HORARIO, background = colors.container, padding = padding) {
            if (tier == WidgetSizeTier.COMPACT) {
                ClassHeroRow(heroContent, titleSize = 20.sp, badgeSize = 64.dp, modifier = GlanceModifier.fillMaxSize())
            } else {
                ClassCard(
                    content = heroContent,
                    status = classCardStatus(state, hero.start.toLocalDate(), today),
                    countdown = classCardCountdown(hero, state, use12h),
                    progress = classProgress(hero, now),
                    code = courses.codeFor(hero),
                    showDetail = showDetail,
                )
            }
        }
        return
    }
    val following = classes.drop(1).take(classRowLimit(tier))
    WidgetFrame(description, WIDGET_DEST_CALENDAR_HORARIO) {
        WidgetHeader("Clases", remainingTodayLabel(classes, today))
        SegmentedGroup(GlanceModifier.defaultWeight()) {
            ClassHeroRow(
                heroContent,
                titleSize = if (tier == WidgetSizeTier.TALL) 24.sp else 22.sp,
                badgeSize = 72.dp,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .background(colors.container)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            following.forEach { event ->
                SegmentGap()
                Segment {
                    CourseBar(courses.colorsFor(event)?.accent ?: GlanceTheme.colors.outline)
                    Spacer(GlanceModifier.width(10.dp))
                    RowText(event.title, event.location)
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = classRowTrailing(event, today, use12h),
                        maxLines = 1,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                    )
                }
            }
        }
    }
}

private data class ClassHeroContent(
    val title: String,
    val overline: String,
    val detail: String,
    val badge: ShapeBadgeText,
    val colors: WidgetCourseColors,
)

@Composable
private fun ClassHeroRow(content: ClassHeroContent, titleSize: TextUnit, badgeSize: Dp, modifier: GlanceModifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            HeroOverline(content)
            Text(
                text = content.title,
                maxLines = 1,
                style = TextStyle(fontSize = titleSize, fontWeight = FontWeight.Bold, color = content.colors.onContainer),
            )
            HeroDetail(content, maxLines = 1)
        }
        Spacer(GlanceModifier.width(10.dp))
        HeroBadge(content, badgeSize, if (badgeSize < 70.dp) 18.sp else 20.sp)
    }
}

/**
 * 2×2 layout: a status pill on top, the countdown as the headline (with how far through the class
 * you are while it runs), then the course name and its time and room.
 */
@Composable
private fun ColumnScope.ClassCard(
    content: ClassHeroContent,
    status: String,
    countdown: WidgetCountdown,
    progress: Float?,
    code: String?,
    showDetail: Boolean,
) {
    val onContainer = content.colors.onContainer
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(status, background = content.colors.accent, color = content.colors.container, fontSize = 11.sp)
        Spacer(GlanceModifier.defaultWeight())
        if (showDetail && code != null) {
            Text(code, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = onContainer))
        }
    }
    Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Bottom) {
        val short = countdown.value.length <= 2
        CountdownHeadline(
            countdown = countdown,
            color = onContainer,
            showLead = showDetail,
            valueSize = when {
                showDetail && short -> 44.sp
                showDetail -> 36.sp
                short -> 34.sp
                else -> 28.sp
            },
        )
        if (progress != null) {
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = content.colors.accent,
                backgroundColor = GlanceTheme.colors.widgetBackground,
            )
        }
    }
    Spacer(GlanceModifier.height(if (showDetail) 8.dp else 4.dp))
    Text(
        text = content.title,
        maxLines = 1,
        style = TextStyle(fontSize = if (showDetail) 15.sp else 13.sp, fontWeight = FontWeight.Bold, color = onContainer),
    )
    if (showDetail) HeroDetail(content, maxLines = 1)
}

@Composable
private fun HeroOverline(content: ClassHeroContent) {
    Text(
        text = content.overline,
        maxLines = 1,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = content.colors.onContainer),
    )
}

@Composable
private fun HeroDetail(content: ClassHeroContent, maxLines: Int) {
    Text(content.detail, maxLines = maxLines, style = TextStyle(fontSize = 12.sp, color = content.colors.onContainer))
}

@Composable
private fun HeroBadge(content: ClassHeroContent, size: Dp, valueSize: TextUnit) {
    // The accent fill with the course container as text color keeps contrast in both themes.
    ShapeBadge(R.drawable.widget_shape_cookie, size, content.colors.accent, content.colors.container, content.badge, valueSize)
}

class ClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClassWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Starts (or stops) the countdown alarm; see BusesWidgetReceiver for why this isn't goAsync().
        scheduleBusWidgetSynchronization(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        scheduleBusWidgetSynchronization(context)
    }
}
