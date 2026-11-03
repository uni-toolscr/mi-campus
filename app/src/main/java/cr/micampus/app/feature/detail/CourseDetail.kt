package cr.micampus.app.feature.detail

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.calendar.ScheduleSlot
import cr.micampus.app.feature.calendar.classTitlesByCode
import cr.micampus.app.feature.calendar.courseKeyOf
import cr.micampus.app.feature.calendar.deriveScheduleSlots
import cr.micampus.app.platform.reminders.ReminderPlanner
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** A course as Horario keys it (the trimmed class title), with what its sheet shows. */
data class CourseDetail(
    val key: String,
    val code: String?,
    val institution: Institution,
    /** Weekly blocks, Monday first. */
    val slots: List<ScheduleSlot>,
    /** Pending exams, quizzes, tasks and activities of this course, soonest first. */
    val evaluations: List<CampusEvent>,
    /** The next class session, for "Editar próxima sesión". */
    val nextSession: CampusEvent?,
)

/** The course [event] belongs to, matched the same way the Calendar colors it. */
fun courseKeyFor(event: CampusEvent, events: List<CampusEvent>): String? {
    val classTitles = events.filter { it.kind == EventKind.CLASS }.mapTo(mutableSetOf()) { it.title.trim() }
    return courseKeyOf(event, classTitlesByCode(events), classTitles)?.takeIf { it in classTitles }
}

fun courseDetail(key: String, events: List<CampusEvent>, now: LocalDateTime): CourseDetail? {
    val classes = events.filter { it.kind == EventKind.CLASS && it.title.trim() == key }
    if (classes.isEmpty()) return null
    val slots = deriveScheduleSlots(classes, now).sortedWith(compareBy<ScheduleSlot> { it.day }.thenBy { it.start })
    return CourseDetail(
        key = key,
        code = classes.firstNotNullOfOrNull { it.courseCode?.trim()?.takeIf(String::isNotEmpty) },
        institution = classes.first().institution,
        slots = slots,
        evaluations = events
            .filter { it.kind != EventKind.CLASS && !it.completed && !it.end.isBefore(now) && courseKeyFor(it, events) == key }
            .sortedBy(CampusEvent::start),
        nextSession = classes.filter { it.end.isAfter(now) }.minByOrNull(CampusEvent::start),
    )
}

/** Sessions of [event]'s course still ahead of it, so an exam shows how many classes are left to prepare. */
fun classesBefore(event: CampusEvent, events: List<CampusEvent>, now: LocalDateTime): List<CampusEvent> {
    if (event.kind == EventKind.CLASS) return emptyList()
    val key = courseKeyFor(event, events) ?: return emptyList()
    return events
        .filter { it.kind == EventKind.CLASS && it.title.trim() == key && it.end.isAfter(now) && it.start.isBefore(event.start) }
        .sortedBy(CampusEvent::start)
}

/** "Examen · en 7 días", "Tarea · mañana", "Quiz · hoy", "Examen · pasado". */
fun eventStatusLabel(event: CampusEvent, now: LocalDateTime): String {
    val kind = eventKindName(event.kind)
    val timing = when {
        event.completed -> "hecha"
        event.end.isBefore(now) -> "pasado"
        else -> when (val days = ChronoUnit.DAYS.between(now.toLocalDate(), event.start.toLocalDate())) {
            in Long.MIN_VALUE..0L -> "hoy"
            1L -> "mañana"
            else -> "en $days días"
        }
    }
    return "$kind · $timing"
}

/** The automatic reminder the planner schedules for [kind], as the event page explains it. */
fun automaticReminderLabel(kind: EventKind): String? = ReminderPlanner.primaryOffset(kind)?.toDays()?.let { days ->
    if (days == 1L) "1 día antes" else "$days días antes"
}

fun eventKindName(kind: EventKind): String = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.TAREA -> "Tarea"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}

/** "Antes de este examen", "Antes de esta tarea". */
fun beforeEventHeading(kind: EventKind): String = when (kind) {
    EventKind.EXAM -> "Antes de este examen"
    EventKind.QUIZ -> "Antes de este quiz"
    EventKind.TAREA -> "Antes de esta entrega"
    else -> "Antes de este evento"
}

fun dayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Lunes"
    DayOfWeek.TUESDAY -> "Martes"
    DayOfWeek.WEDNESDAY -> "Miércoles"
    DayOfWeek.THURSDAY -> "Jueves"
    DayOfWeek.FRIDAY -> "Viernes"
    DayOfWeek.SATURDAY -> "Sábado"
    DayOfWeek.SUNDAY -> "Domingo"
}

/** Two letters for the course badge: the code's prefix ("MA" from MA-1002), else the title's initials. */
fun courseMonogram(key: String, code: String?): String =
    code?.takeWhile(Char::isLetter)?.take(2)?.uppercase()?.takeIf { it.length == 2 }
        ?: key.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
