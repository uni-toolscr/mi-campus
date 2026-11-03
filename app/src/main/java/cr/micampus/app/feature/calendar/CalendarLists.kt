package cr.micampus.app.feature.calendar

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.isExpired
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

private val costaRica = Locale.forLanguageTag("es-CR")

// Spelled out instead of DateTimeFormatter so labels don't depend on the device's CLDR data ("oct." vs "oct").
private val monthNames = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
private val monthAbbreviations = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

internal fun weekdayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "lunes"
    DayOfWeek.TUESDAY -> "martes"
    DayOfWeek.WEDNESDAY -> "miércoles"
    DayOfWeek.THURSDAY -> "jueves"
    DayOfWeek.FRIDAY -> "viernes"
    DayOfWeek.SATURDAY -> "sábado"
    DayOfWeek.SUNDAY -> "domingo"
}

internal fun weekdayAbbreviation(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Lun"
    DayOfWeek.TUESDAY -> "Mar"
    DayOfWeek.WEDNESDAY -> "Mié"
    DayOfWeek.THURSDAY -> "Jue"
    DayOfWeek.FRIDAY -> "Vie"
    DayOfWeek.SATURDAY -> "Sáb"
    DayOfWeek.SUNDAY -> "Dom"
}

private fun monthAbbreviation(date: LocalDate) = monthAbbreviations[date.monthValue - 1]

private fun String.capitalized() = replaceFirstChar { it.titlecase(costaRica) }

/** "26 oct – 1 nov". */
fun weekRangeLabel(weekStart: LocalDate): String {
    val end = weekStart.plusDays(6)
    return "${weekStart.dayOfMonth} ${monthAbbreviation(weekStart)} – ${end.dayOfMonth} ${monthAbbreviation(end)}"
}

/** Exams, quizzes and assignments: the events that mark a day as having something due. */
fun isDeadline(kind: EventKind): Boolean = kind == EventKind.EXAM || kind == EventKind.QUIZ || kind == EventKind.TAREA

data class WeekStripDay(
    val date: LocalDate,
    val classKeys: List<String>,
    val hasDeadline: Boolean,
    val isToday: Boolean,
    val isPast: Boolean,
)

/** Mon–Sun cells for the Semana strip; [WeekStripDay.classKeys] holds one course key per class session, in start order. */
fun weekStripDays(weekStart: LocalDate, events: List<CampusEvent>, today: LocalDate): List<WeekStripDay> {
    val byDay = events.groupBy { it.start.toLocalDate() }
    return weekDays(weekStart).map { date ->
        val dayEvents = byDay[date].orEmpty().sortedBy(CampusEvent::start)
        WeekStripDay(
            date = date,
            classKeys = dayEvents.filter { it.kind == EventKind.CLASS }.map { it.title.trim() },
            hasDeadline = dayEvents.any { isDeadline(it.kind) },
            isToday = date == today,
            isPast = date.isBefore(today),
        )
    }
}

sealed interface WeekListRow {
    data class PastFold(val from: LocalDate, val to: LocalDate, val classCount: Int, val otherCount: Int, val expanded: Boolean) : WeekListRow
    data class DayHeader(val date: LocalDate) : WeekListRow
    data class Classes(val date: LocalDate, val classes: List<CampusEvent>) : WeekListRow
    data class Event(val event: CampusEvent, val index: Int, val count: Int) : WeekListRow
    data class Empty(val date: LocalDate) : WeekListRow
}

/**
 * Semana list: a header per day, that day's classes as one chip row, then the other events. In the
 * current week the days before [today] collapse into a single [WeekListRow.PastFold] unless [showPast].
 */
fun weekListRows(weekStart: LocalDate, events: List<CampusEvent>, today: LocalDate, showPast: Boolean): List<WeekListRow> {
    val days = weekDays(weekStart)
    val weekEnd = weekStart.plusDays(7)
    val byDay = events.asSequence()
        .filter { val d = it.start.toLocalDate(); !d.isBefore(weekStart) && d.isBefore(weekEnd) }
        .sortedBy(CampusEvent::start)
        .groupBy { it.start.toLocalDate() }
    val pastDays = days.filter { it.isBefore(today) }.takeIf { today.isBefore(weekEnd) }.orEmpty()
    return buildList {
        if (pastDays.isNotEmpty()) {
            val pastEvents = pastDays.flatMap { byDay[it].orEmpty() }
            val classes = pastEvents.count { it.kind == EventKind.CLASS }
            add(WeekListRow.PastFold(pastDays.first(), pastDays.last(), classes, pastEvents.size - classes, showPast))
        }
        days.filter { showPast || it !in pastDays }.forEach { day ->
            add(WeekListRow.DayHeader(day))
            val dayEvents = byDay[day].orEmpty()
            val (classes, others) = dayEvents.partition { it.kind == EventKind.CLASS }
            if (classes.isNotEmpty()) add(WeekListRow.Classes(day, classes))
            others.forEachIndexed { index, event -> add(WeekListRow.Event(event, index, others.size)) }
            if (dayEvents.isEmpty()) add(WeekListRow.Empty(day))
        }
    }
}

/** "Lun 26 – Mar 27 · 3 clases"; a single day drops the range. */
fun pastFoldLabel(fold: WeekListRow.PastFold): String {
    fun day(date: LocalDate) = "${weekdayAbbreviation(date.dayOfWeek)} ${date.dayOfMonth}"
    val range = if (fold.from == fold.to) day(fold.from) else "${day(fold.from)} – ${day(fold.to)}"
    val counts = listOfNotNull(
        fold.classCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "clase" else "clases"}" },
        fold.otherCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "evento" else "eventos"}" },
    ).ifEmpty { listOf("Sin actividades") }
    return "$range · ${counts.joinToString(" · ")}"
}

/** "Hoy miércoles 28", "Mañana jueves 29", "Viernes 30". */
fun weekDayHeader(date: LocalDate, today: LocalDate): String {
    val name = "${weekdayName(date.dayOfWeek)} ${date.dayOfMonth}"
    return when (date) {
        today -> "Hoy $name"
        today.plusDays(1) -> "Mañana $name"
        else -> name.capitalized()
    }
}

/** Trailing badge for a non-class Semana event, or null when its kind has nothing due. */
fun weekEventBadge(kind: EventKind): String? = when (kind) {
    EventKind.TAREA -> "Entrega"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.CLASS, EventKind.ACTIVITY, EventKind.TRANSIT -> null
}

sealed interface AgendaRow {
    data class PastFold(val count: Int, val expanded: Boolean) : AgendaRow
    data class Month(val month: YearMonth) : AgendaRow
    data class DayHeader(val date: LocalDate) : AgendaRow
    data class Event(val event: CampusEvent, val index: Int, val count: Int, val past: Boolean) : AgendaRow
}

/**
 * Agenda list grouped by start date, with a month divider whenever the month changes (including
 * before the first group when it isn't the current month). With [pendingOnly] the expired events
 * hide behind a leading [AgendaRow.PastFold] unless [showPast].
 */
fun agendaRows(events: List<CampusEvent>, now: LocalDateTime, pendingOnly: Boolean, showPast: Boolean): List<AgendaRow> {
    val sorted = events.sortedWith(compareBy<CampusEvent> { it.start }.thenBy { it.title })
    val pastCount = sorted.count { it.isExpired(now) }
    val visible = if (pendingOnly && !showPast) sorted.filterNot { it.isExpired(now) } else sorted
    return buildList {
        if (pendingOnly && pastCount > 0) add(AgendaRow.PastFold(pastCount, showPast))
        var previousMonth = YearMonth.from(now)
        visible.groupBy { it.start.toLocalDate() }.forEach { (date, dayEvents) ->
            val month = YearMonth.from(date)
            if (month != previousMonth) add(AgendaRow.Month(month))
            previousMonth = month
            add(AgendaRow.DayHeader(date))
            dayEvents.forEachIndexed { index, event -> add(AgendaRow.Event(event, index, dayEvents.size, event.isExpired(now))) }
        }
    }
}

/** "MAÑANA · JUE 29 OCT", "HOY · MIÉ 28 OCT", "VIERNES 30 OCT". */
fun agendaDayHeader(date: LocalDate, today: LocalDate): String {
    val short = "${weekdayAbbreviation(date.dayOfWeek)} ${date.dayOfMonth} ${monthAbbreviation(date)}"
    val label = when (date) {
        today -> "Hoy · $short"
        today.plusDays(1) -> "Mañana · $short"
        today.minusDays(1) -> "Ayer · $short"
        else -> "${weekdayName(date.dayOfWeek)} ${date.dayOfMonth} ${monthAbbreviation(date)}"
    }
    return label.uppercase(costaRica)
}

/** "Noviembre", or "Enero 2027" outside the current year. */
fun monthDividerLabel(month: YearMonth, today: LocalDate): String {
    val name = monthNames[month.monthValue - 1].capitalized()
    return if (month.year == today.year) name else "$name ${month.year}"
}

data class Countdown(val text: String, val urgent: Boolean)

/** Relative start badge: "Ahora", "en 25 min", "en 3 h", "Hoy", "Mañana", "en 2 días"; null once the event ended. */
fun agendaCountdown(event: CampusEvent, now: LocalDateTime): Countdown? {
    if (event.isExpired(now)) return null
    if (!event.start.isAfter(now)) return Countdown("Ahora", urgent = true)
    val until = Duration.between(now, event.start)
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), event.start.toLocalDate())
    val text = when {
        until.toMinutes() < 60 -> "en ${until.toMinutes().coerceAtLeast(1)} min"
        until.toHours() < 12 -> "en ${until.toHours()} h"
        days == 0L -> "Hoy"
        days == 1L -> "Mañana"
        else -> "en $days días"
    }
    return Countdown(text, urgent = until < Duration.ofDays(2))
}

/** Course code (lowercased) → class title, so exams and assignments can borrow their course's color. */
fun classTitlesByCode(events: List<CampusEvent>): Map<String, String> = events.asSequence()
    .filter { it.kind == EventKind.CLASS && !it.courseCode.isNullOrBlank() && it.title.isNotBlank() }
    .associate { it.courseCode!!.trim().lowercase() to it.title.trim() }

/**
 * The course an event belongs to, as a class title: its own title for classes, otherwise the class
 * sharing its course code, otherwise the longest class title its title mentions.
 */
fun courseKeyOf(event: CampusEvent, titlesByCode: Map<String, String>, classTitles: Collection<String>): String? {
    if (event.kind == EventKind.CLASS) return event.title.trim()
    event.courseCode?.trim()?.lowercase()?.let(titlesByCode::get)?.let { return it }
    return classTitles.filter { it.isNotEmpty() && event.title.contains(it, ignoreCase = true) }.maxByOrNull(String::length)
}
