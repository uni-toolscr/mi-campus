package cr.micampus.app.feature.calendar

import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class ClassBlock(
    val day: DayOfWeek = DayOfWeek.MONDAY,
    val start: LocalTime = LocalTime.of(8, 0),
    val end: LocalTime = LocalTime.of(9, 50),
) {
    val isValid: Boolean get() = end.isAfter(start)
}

data class ClassScheduleSpec(
    val title: String,
    val institution: Institution,
    val location: String = "",
    val notes: String = "",
    val semesterStart: LocalDate,
    val semesterEnd: LocalDate,
    val blocks: List<ClassBlock>,
) {
    val isValid: Boolean
        get() = title.isNotBlank() && !semesterEnd.isBefore(semesterStart) && blocks.isNotEmpty() &&
            blocks.all(ClassBlock::isValid) && blocks.toSet().size == blocks.size
}

object ClassScheduleGenerator {
    fun generate(spec: ClassScheduleSpec): List<CampusEvent> {
        if (!spec.isValid) return emptyList()
        val title = spec.title.trim()
        return generateSequence(spec.semesterStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(spec.semesterEnd) }
            .flatMap { date ->
                spec.blocks.asSequence().filter { it.day == date.dayOfWeek }.map { block ->
                    CampusEvent(
                        id = "clase-" + stableId(title, date.toString(), block.start.toString(), block.end.toString()),
                        title = title,
                        institution = spec.institution,
                        kind = EventKind.CLASS,
                        start = date.atTime(block.start),
                        end = date.atTime(block.end),
                        location = spec.location.trim(),
                        notes = spec.notes.trim(),
                    )
                }
            }
            .sortedBy(CampusEvent::start)
            .toList()
    }

    private fun stableId(vararg values: String): String =
        MessageDigest.getInstance("SHA-256").digest(values.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
