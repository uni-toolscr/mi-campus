package cr.micampus.app.platform.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import cr.micampus.app.MainActivity
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.R
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.home.HeroTiming
import cr.micampus.app.feature.home.HomeCourse
import cr.micampus.app.feature.home.heroTimingLabel
import cr.micampus.app.feature.home.homeCourses
import cr.micampus.app.feature.home.matchCourseKey
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Shared size tiers for all home-screen widgets. The responsive sizes advertised by each provider
 * are 110 x 110, 150 x 150, 110 x 180, 180 x 110, 180 x 180, 250 x 110, 250 x 180, and 250 x 280 dp.
 * SQUARE and NARROW draw a single card, COMPACT a single row, MEDIUM and TALL a header plus a list.
 * Widgets default to 2 x 2, which lands on SQUARE (150 x 150) or NARROW depending on the launcher grid.
 */
enum class WidgetSizeTier { SQUARE, NARROW, COMPACT, MEDIUM, TALL }

val WidgetSizeTier.showsList: Boolean
    get() = this == WidgetSizeTier.MEDIUM || this == WidgetSizeTier.TALL

val WIDGET_RESPONSIVE_SIZES = setOf(
    DpSize(110.dp, 110.dp),
    DpSize(150.dp, 150.dp),
    DpSize(110.dp, 180.dp),
    DpSize(180.dp, 110.dp),
    DpSize(180.dp, 180.dp),
    DpSize(250.dp, 110.dp),
    DpSize(250.dp, 180.dp),
    DpSize(250.dp, 280.dp),
)

fun widgetSizeTier(size: DpSize): WidgetSizeTier = when {
    size.width < 180.dp && size.height < 180.dp -> WidgetSizeTier.SQUARE
    size.height < 180.dp -> WidgetSizeTier.COMPACT
    size.width < 250.dp -> WidgetSizeTier.NARROW
    size.height < 280.dp -> WidgetSizeTier.MEDIUM
    else -> WidgetSizeTier.TALL
}

/**
 * Whether a single-card size has room for its supporting lines (lead label, details, next items).
 * The 110 x 110 minimum keeps only the headline so nothing clips.
 */
fun widgetCardShowsDetail(size: DpSize): Boolean = size.height >= 150.dp

// --- Widget deep-link targets -------------------------------------------------
// Carried as a MainActivity intent extra so a widget tap lands on the matching
// screen instead of Inicio. MainActivity reads and routes these.

const val EXTRA_WIDGET_DESTINATION = "cr.micampus.app.widget.DESTINATION"
const val WIDGET_DEST_CALENDAR_HORARIO = "calendar_horario"
const val WIDGET_DEST_CALENDAR_AGENDA = "calendar_agenda"
const val WIDGET_DEST_TRANSPORT = "transport"

// --- Shared widget preferences ------------------------------------------------
// Each widget instance owns its own PreferencesGlanceStateDefinition datastore, so
// these key names are reused across widget types without colliding.

/** Per-widget institution filter: "UCR" | "UNA" | "BOTH". */
val WIDGET_INSTITUTIONS_KEY: Preferences.Key<String> = stringPreferencesKey("institutions")

/** Bus widget only: comma-joined [directionKey] values; absent/blank = all directions. */
val BUS_WIDGET_DIRECTIONS_KEY: Preferences.Key<String> = stringPreferencesKey("directions")

const val WIDGET_FILTER_UCR = "UCR"
const val WIDGET_FILTER_UNA = "UNA"
const val WIDGET_FILTER_BOTH = "BOTH"

fun institutionsFor(filter: String): List<Institution> = when (filter) {
    WIDGET_FILTER_UCR -> listOf(Institution.UCR)
    WIDGET_FILTER_UNA -> listOf(Institution.UNA)
    else -> listOf(Institution.UCR, Institution.UNA)
}

fun widgetInstitutionLabel(context: Context, filter: String): String = when (filter) {
    WIDGET_FILTER_UCR -> context.getString(R.string.widget_institution_ucr)
    WIDGET_FILTER_UNA -> context.getString(R.string.widget_institution_una)
    else -> context.getString(R.string.widget_institution_both)
}

/** Parse the persisted direction filter into a set of [directionKey]s (empty = all). */
fun parseDirectionFilter(raw: String?): Set<String> =
    raw?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

// --- Course colors and time labels --------------------------------------------

/** Day/night colors for one course, the same hues the app uses for it in Inicio and Horario. */
data class WidgetCourseColors(val container: ColorProvider, val onContainer: ColorProvider, val accent: ColorProvider)

fun widgetCourseColors(index: Int): WidgetCourseColors {
    val (dayContainer, dayOn) = coursePalette(dark = false)[index]
    val (nightContainer, nightOn) = coursePalette(dark = true)[index]
    return WidgetCourseColors(
        container = ColorProvider(day = dayContainer, night = nightContainer),
        onContainer = ColorProvider(day = dayOn, night = nightOn),
        // Marks on the widget surface use the opposite theme's container, as courseAccentPalette() does in the app.
        accent = ColorProvider(day = nightContainer, night = dayContainer),
    )
}

/** Matches events to courses and palette indices exactly like Inicio, so a course keeps its color on the home screen. */
class WidgetCourses(private val courses: List<HomeCourse>, private val colorIndex: Map<String, Int>) {
    fun keyFor(event: CampusEvent): String? = matchCourseKey(event, courses)

    fun colorsFor(event: CampusEvent): WidgetCourseColors? = keyFor(event)?.let(colorIndex::get)?.let(::widgetCourseColors)

    fun codeFor(event: CampusEvent): String? =
        event.courseCode?.trim()?.takeIf(String::isNotEmpty) ?: keyFor(event)?.let { key -> courses.firstOrNull { it.key == key }?.code }

    companion object {
        fun of(events: List<CampusEvent>, styles: List<CourseStyle>, now: LocalDateTime): WidgetCourses {
            val courses = homeCourses(events, now)
            val indices = assignCourseColors(courses.map(HomeCourse::key), styles.associateBy(CourseStyle::courseKey), coursePalette(dark = false).size)
            return WidgetCourses(courses, indices)
        }
    }
}

suspend fun loadWidgetCourses(app: MiCampusApplication, events: List<CampusEvent>, now: LocalDateTime): WidgetCourses {
    val styles = runCatching { app.container.events.courseStyles.first() }.getOrDefault(emptyList())
    return WidgetCourses.of(events, styles, now)
}

/** "Hoy 23:00", "Mañana 8:00", "Mié 8:00", or a full date beyond the coming week, as Inicio phrases it. */
fun widgetWhenLabel(start: LocalDateTime, today: LocalDate, use12h: Boolean): String =
    if (start.toLocalDate() == today) {
        "Hoy ${start.format(timeFormatter(use12h))}"
    } else {
        heroTimingLabel(HeroTiming.Later(start), today, use12h)
    }

private val costaRica = Locale.forLanguageTag("es-CR")
private val badgeClock12 = DateTimeFormatter.ofPattern("h:mm", costaRica)
private val badgeMeridiem = DateTimeFormatter.ofPattern("a", costaRica)
private val badgeDate = DateTimeFormatter.ofPattern("d MMM", costaRica)
private val shortDays = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

/** A clock time split for a badge: "8:00" plus "a. m." in 12h mode, since the full string doesn't fit the shape. */
data class BadgeClock(val time: String, val meridiem: String?)

fun badgeClock(time: LocalDateTime, use12h: Boolean): BadgeClock =
    if (use12h) BadgeClock(time.format(badgeClock12), time.format(badgeMeridiem).replace('\u202F', ' ').replace('\u00A0', ' ')) else BadgeClock(time.format(timeFormatter(false)), null)

/** Lowercase day for a badge: "hoy", "mañana", "mié" within the week, else "9 nov". */
fun widgetDayLabel(date: LocalDate, today: LocalDate): String = when (val days = ChronoUnit.DAYS.between(today, date)) {
    0L -> "hoy"
    1L -> "mañana"
    in 2L..6L -> shortDays[date.dayOfWeek.value - 1]
    else -> date.format(badgeDate).replace(".", "")
}

/** Days from [today] to [date] as a short badge: "en 9 días". */
fun widgetDaysAwayLabel(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return if (days == 1L) "en 1 día" else "en $days días"
}

// --- Shared Glance composables ------------------------------------------------

private val GroupCorner = 20.dp
private val SegmentCorner = 6.dp
private val SegmentGap = 2.dp

/**
 * Outer widget frame: taps open the app on [launchDestination]. The launcher rounds the outer
 * corners on Android 12+, so the frame itself stays square.
 */
@Composable
fun WidgetFrame(
    description: String,
    launchDestination: String,
    background: ColorProvider = GlanceTheme.colors.widgetBackground,
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val launchIntent = Intent(context, MainActivity::class.java).putExtra(EXTRA_WIDGET_DESTINATION, launchDestination)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .padding(padding)
            .clickable(actionStartActivity(launchIntent))
            .semantics { contentDescription = description },
        content = content,
    )
}

/** Small label row above a widget's content, with optional trailing context such as a count. */
@Composable
fun WidgetHeader(label: String, trailing: String? = null, iconRes: Int? = null) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(16.dp),
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/**
 * A segmented group: its large corner radius clips the stacked [Segment]s so only the group's outer
 * corners are round while seams between items keep a small radius, matching the app's grouped
 * lists. Corner clipping applies on API 31+; on 26–30 the group renders square.
 */
@Composable
fun SegmentedGroup(modifier: GlanceModifier = GlanceModifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth().cornerRadius(GroupCorner), content = content)
}

@Composable
fun SegmentGap() {
    Spacer(GlanceModifier.height(SegmentGap))
}

/** One item in a [SegmentedGroup]. */
@Composable
fun Segment(
    modifier: GlanceModifier = GlanceModifier,
    background: ColorProvider = GlanceTheme.colors.surfaceVariant,
    horizontalPadding: Dp = 14.dp,
    verticalPadding: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .cornerRadius(SegmentCorner)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Course color bar leading a list row. */
@Composable
fun CourseBar(color: ColorProvider, height: Dp = 28.dp) {
    Box(GlanceModifier.width(4.dp).height(height).background(color).cornerRadius(2.dp)) {}
}

@Composable
fun CourseDot(color: ColorProvider) {
    Box(GlanceModifier.size(8.dp).background(color).cornerRadius(4.dp)) {}
}

/** Two-line list row text: [title] over a muted [supporting] line. */
@Composable
fun RowScope.RowText(title: String, supporting: String?) {
    Column(GlanceModifier.defaultWeight()) {
        Text(
            text = title,
            maxLines = 1,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
        )
        if (!supporting.isNullOrBlank()) {
            Text(
                text = supporting,
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/** Status pill: urgent ones ("Hoy", "Mañana") use the error container, like the app's badges. */
@Composable
fun WidgetPill(text: String, urgent: Boolean) {
    StatusPill(
        text = text,
        background = if (urgent) GlanceTheme.colors.errorContainer else GlanceTheme.colors.primaryContainer,
        color = if (urgent) GlanceTheme.colors.onErrorContainer else GlanceTheme.colors.onPrimaryContainer,
    )
}

@Composable
fun StatusPill(text: String, background: ColorProvider, color: ColorProvider, fontSize: TextUnit = 12.sp) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = color),
        modifier = GlanceModifier
            .background(background)
            .cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * The headline of a 2 x 2 card: a short [lead] ("quedan", "sale en") over a large [value] with an
 * optional [unit] beside it ("min", "a. m."), e.g. "quedan / 25 min".
 */
data class WidgetCountdown(val lead: String, val value: String, val unit: String?)

@Composable
fun CountdownHeadline(countdown: WidgetCountdown, color: ColorProvider, showLead: Boolean, valueSize: TextUnit) {
    if (showLead) {
        Text(countdown.lead, maxLines = 1, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color))
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(countdown.value, maxLines = 1, style = TextStyle(fontSize = valueSize, fontWeight = FontWeight.Bold, color = color))
        countdown.unit?.let { unit ->
            Text(
                text = unit,
                maxLines = 1,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color),
                modifier = GlanceModifier.padding(start = 3.dp, bottom = 4.dp),
            )
        }
    }
}

/** Text drawn on a [ShapeBadge]: an optional small [label] above the [value] and an optional [unit] below it. */
data class ShapeBadgeText(val label: String?, val value: String, val unit: String?)

/** An expressive shape (cookie, sunny) with centered text, used for countdowns and departure times. */
@Composable
fun ShapeBadge(
    shapeRes: Int,
    size: Dp,
    shapeColor: ColorProvider,
    textColor: ColorProvider,
    text: ShapeBadgeText,
    valueSize: TextUnit,
) {
    Box(modifier = GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(shapeRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(shapeColor),
            modifier = GlanceModifier.size(size),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val small = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
            text.label?.let { Text(it, maxLines = 1, style = small) }
            Text(text.value, maxLines = 1, style = TextStyle(fontSize = valueSize, fontWeight = FontWeight.Bold, color = textColor))
            text.unit?.let { Text(it, maxLines = 1, style = small) }
        }
    }
}

/** Centered message for a widget with nothing to show. */
@Composable
fun ColumnScope.WidgetEmpty(message: String, iconRes: Int) {
    Column(
        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(24.dp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(message, maxLines = 2, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
    }
}
