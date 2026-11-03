package cr.micampus.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.institution.AssetTransportRepository
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.data.institution.directionKey
import cr.micampus.app.data.institution.upcomingDepartures
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.feature.calendar.deriveScheduleSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal fun homeUpcomingEvents(events: List<CampusEvent>, now: LocalDateTime, limit: Int = 5): List<CampusEvent> =
    events.filter { it.kind != EventKind.CLASS && !it.end.isBefore(now) }.take(limit)

/** The next bus, plus the one after it on the same route ("luego 22:30"), or null when it's the last of the day. */
data class HomeBus(val next: UpcomingDeparture, val following: UpcomingDeparture?)

sealed interface HeroTiming {
    data object Now : HeroTiming
    data class StartsIn(val minutes: Long) : HeroTiming
    data class Later(val start: LocalDateTime) : HeroTiming
}

/**
 * [courseKey] lets a class hero open its course sheet and wear the course's color; [evaluation] is
 * the course's next exam or task within a week ("Quiz 2 el miércoles").
 */
data class HomeHero(
    val event: CampusEvent,
    val timing: HeroTiming,
    val courseKey: String? = null,
    val courseCode: String? = null,
    val evaluation: CampusEvent? = null,
)

/** A course as Horario keys it (trimmed class title), with the first course code seen on its classes. */
data class HomeCourse(val key: String, val code: String?)

enum class HomeEventBadge { TODAY, TOMORROW, EXAM, QUIZ }

data class HomeEventItem(val event: CampusEvent, val courseKey: String?, val badge: HomeEventBadge?)

data class WeekSummary(val pending: Int = 0, val exams: Int = 0, val quizzes: Int = 0)

/**
 * The class in progress, else the next class (today or later); without upcoming classes, the next
 * non-class event. Classes are concrete dated occurrences, so picking by time is enough.
 */
internal fun homeHero(events: List<CampusEvent>, now: LocalDateTime): HomeHero? {
    val event = events.filter { it.kind == EventKind.CLASS && it.end.isAfter(now) }.minByOrNull(CampusEvent::start)
        ?: homeUpcomingEvents(events.filterNot(CampusEvent::completed), now, limit = 1).firstOrNull()
        ?: return null
    return HomeHero(event, heroTiming(event, now))
}

internal fun heroTiming(event: CampusEvent, now: LocalDateTime): HeroTiming = when {
    !event.start.isAfter(now) -> HeroTiming.Now
    event.start.toLocalDate() == now.toLocalDate() -> HeroTiming.StartsIn(Duration.between(now, event.start).toMinutes())
    else -> HeroTiming.Later(event.start)
}

internal fun homeCourses(events: List<CampusEvent>, now: LocalDateTime): List<HomeCourse> {
    val codes = events.filter { it.kind == EventKind.CLASS }
        .groupBy { it.title.trim() }
        .mapValues { (_, classes) -> classes.firstNotNullOfOrNull { it.courseCode?.trim()?.takeIf(String::isNotEmpty) } }
    return deriveScheduleSlots(events, now).map { it.title }.distinct().map { HomeCourse(it, codes[it]) }
}

/** Matches by course code first, then by the event title containing a course title (longest wins). */
internal fun matchCourseKey(event: CampusEvent, courses: List<HomeCourse>): String? {
    if (event.kind == EventKind.CLASS) return courses.firstOrNull { it.key == event.title.trim() }?.key
    val code = event.courseCode?.trim()?.takeIf(String::isNotEmpty)
    if (code != null) courses.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { return it.key }
    return courses.filter { event.title.contains(it.key, ignoreCase = true) }.maxByOrNull { it.key.length }?.key
}

/** Due today/tomorrow wins over the kind badge, since urgency matters more than what it is. */
internal fun homeEventBadge(event: CampusEvent, today: LocalDate): HomeEventBadge? {
    val days = ChronoUnit.DAYS.between(today, event.start.toLocalDate())
    return when {
        days <= 0 -> HomeEventBadge.TODAY
        days == 1L -> HomeEventBadge.TOMORROW
        event.kind == EventKind.EXAM -> HomeEventBadge.EXAM
        event.kind == EventKind.QUIZ -> HomeEventBadge.QUIZ
        else -> null
    }
}

/** Non-class events not yet over that start before the end of this Sunday. */
internal fun weekSummary(events: List<CampusEvent>, now: LocalDateTime): WeekSummary {
    val weekEnd = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusDays(1).atStartOfDay()
    val pending = events.filter { it.kind != EventKind.CLASS && !it.completed && !it.end.isBefore(now) && it.start.isBefore(weekEnd) }
    return WeekSummary(
        pending = pending.size,
        exams = pending.count { it.kind == EventKind.EXAM },
        quizzes = pending.count { it.kind == EventKind.QUIZ },
    )
}

/** The hero class's next pending evaluation, if it falls within the coming week. */
internal fun heroEvaluation(courseKey: String, events: List<CampusEvent>, courses: List<HomeCourse>, now: LocalDateTime): CampusEvent? =
    events.filter { it.kind != EventKind.CLASS && !it.completed && !it.end.isBefore(now) && it.start.isBefore(now.plusDays(7)) }
        .filter { matchCourseKey(it, courses) == courseKey }
        .minByOrNull(CampusEvent::start)

internal fun homeState(
    events: List<CampusEvent>,
    styles: List<CourseStyle>,
    bus: HomeBus?,
    use12hClock: Boolean,
    now: LocalDateTime,
    suggestAulaVirtual: Boolean = false,
): HomeUiState {
    val courses = homeCourses(events, now)
    val stylesByKey = styles.associateBy(CourseStyle::courseKey)
    val hero = homeHero(events, now)?.let { hero ->
        val key = matchCourseKey(hero.event, courses) ?: return@let hero
        hero.copy(
            courseKey = key,
            courseCode = courses.firstOrNull { it.key == key }?.code ?: hero.event.courseCode,
            evaluation = if (hero.event.kind == EventKind.CLASS) heroEvaluation(key, events, courses, now) else null,
        )
    }
    val upcoming = homeUpcomingEvents(events, now, limit = 6).filterNot { it.id == hero?.event?.id }.take(5)
    return HomeUiState(
        loading = false,
        now = now,
        hero = hero,
        bus = bus,
        gettingStarted = events.isEmpty(),
        suggestAulaVirtual = suggestAulaVirtual,
        week = weekSummary(events, now),
        events = upcoming.map { HomeEventItem(it, matchCourseKey(it, courses), homeEventBadge(it, now.toLocalDate())) },
        courseKeys = courses.map(HomeCourse::key),
        courseStyles = stylesByKey,
        use12hClock = use12hClock,
    )
}

data class HomeUiState(
    val loading: Boolean = true,
    val now: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES),
    val hero: HomeHero? = null,
    val bus: HomeBus? = null,
    /** No events at all yet: Inicio shows the "Arma tu semestre" checklist instead of the dashboard. */
    val gettingStarted: Boolean = false,
    /** UNA is enabled but Aula Virtual isn't connected, so the checklist offers connecting it. */
    val suggestAulaVirtual: Boolean = false,
    val week: WeekSummary = WeekSummary(),
    val events: List<HomeEventItem> = emptyList(),
    val courseKeys: List<String> = emptyList(),
    val courseStyles: Map<String, CourseStyle> = emptyMap(),
    val use12hClock: Boolean = false,
)

class HomeViewModel(
    events: EventRepository,
    settings: SettingsStore,
    private val transport: AssetTransportRepository,
    private val aulaVirtualConnected: () -> Boolean = { false },
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(events.confirmedEvents, events.courseStyles, settings.settings, minuteTicks()) { allEvents, styles, appSettings, now ->
        val bus = transport.upcomingDepartures(appSettings.selectedInstitutions(), now, limit = 1).firstOrNull()?.let { next ->
            val sameRoute = setOf(directionKey(next.institution, next.directionId))
            HomeBus(next, transport.upcomingDepartures(listOf(next.institution), next.departure, limit = 1, directionIds = sameRoute).firstOrNull())
        }
        val suggestAulaVirtual = allEvents.isEmpty() && appSettings.unaEnabled && !runCatching(aulaVirtualConnected).getOrDefault(false)
        homeState(allEvents, styles, bus, appSettings.use12hClock, now, suggestAulaVirtual)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** The current minute, re-emitted at each minute boundary so countdowns stay live. */
    private fun minuteTicks(): Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now(clock)
            emit(now.truncatedTo(ChronoUnit.MINUTES))
            delay(60_000L - now.second * 1_000L - now.nano / 1_000_000)
        }
    }
}
