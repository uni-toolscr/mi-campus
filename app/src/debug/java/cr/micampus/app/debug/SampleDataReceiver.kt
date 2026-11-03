package cr.micampus.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cr.micampus.app.MiCampusApplication
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** Debug builds only: enables both institutions and stores a realistic semester week relative to today. */
class SampleDataReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MiCampusApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now()
                container.settings.setInstitutions(ucr = true, una = true)
                container.settings.setSemesterRange(today.minusWeeks(6), today.plusWeeks(10))
                container.events.saveAll(sampleClasses(today) + sampleEvents(today))
            } finally {
                pending.finish()
            }
        }
    }
}

private data class SampleCourse(val title: String, val code: String, val day: DayOfWeek, val start: LocalTime, val end: LocalTime, val location: String)

private val courses = listOf(
    SampleCourse("Cálculo II", "MA-1002", DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(11, 50), "Aula 204 · Ciencias Básicas"),
    SampleCourse("Cálculo II", "MA-1002", DayOfWeek.WEDNESDAY, LocalTime.of(10, 0), LocalTime.of(11, 50), "Aula 204 · Ciencias Básicas"),
    SampleCourse("Física General", "FS-0210", DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(15, 50), "Laboratorio 3"),
    SampleCourse("Química General", "QU-0100", DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 50), "Aula 110"),
    SampleCourse("Química General", "QU-0100", DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(9, 50), "Aula 110"),
    SampleCourse("Inglés intensivo II", "LM-1030", DayOfWeek.WEDNESDAY, LocalTime.of(15, 0), LocalTime.of(16, 40), "Escuela de Lenguas · 302"),
    SampleCourse("Programación I", "CI-0112", DayOfWeek.FRIDAY, LocalTime.of(13, 0), LocalTime.of(15, 50), "Laboratorio C"),
)

private fun sampleClasses(today: LocalDate): List<CampusEvent> {
    val firstMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(2)
    return (0L until 10L).flatMap { week ->
        courses.map { course ->
            val date = firstMonday.plusWeeks(week).with(TemporalAdjusters.nextOrSame(course.day))
            CampusEvent(
                id = "sample-class-${course.code}-$date",
                title = course.title,
                institution = Institution.UCR,
                kind = EventKind.CLASS,
                start = date.atTime(course.start),
                end = date.atTime(course.end),
                location = course.location,
                courseCode = course.code,
            )
        }
    }
}

private fun sampleEvents(today: LocalDate): List<CampusEvent> {
    fun event(id: String, title: String, kind: EventKind, code: String?, start: LocalDateTime, minutes: Long, location: String = "", institution: Institution = Institution.UCR) =
        CampusEvent("sample-$id", title, institution, kind, start, start.plusMinutes(minutes), location = location, courseCode = code)
    val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    return listOf(
        event("lab3", "Laboratorio 3 · Física", EventKind.TAREA, "FS-0210", today.minusDays(2).atTime(23, 0), 59),
        event("quiz1", "Quiz 1 · Química", EventKind.QUIZ, "QU-0100", today.minusDays(6).atTime(8, 0), 30, "Aula 110"),
        event("tarea4", "Tarea 4 · Física", EventKind.TAREA, "FS-0210", today.plusDays(1).atTime(23, 0), 59),
        event("quiz2", "Quiz 2 · Química", EventKind.QUIZ, "QU-0100", today.plusDays(2).atTime(8, 0), 30, "Aula 110"),
        event("parcial2", "Parcial 2 · Cálculo II", EventKind.EXAM, "MA-1002", nextMonday.atTime(13, 0), 120, "Aula 204"),
        event("proyecto", "Proyecto · Programación I", EventKind.TAREA, "CI-0112", today.plusDays(16).atTime(23, 0), 59),
        event("ensayo", "Ensayo · Inglés", EventKind.TAREA, "LM-1030", today.plusDays(16).atTime(22, 0), 59),
        event("feria", "Feria vocacional", EventKind.ACTIVITY, null, today.plusDays(9).atTime(9, 0), 240, "Plaza de la Libertad", Institution.UNA),
    )
}
