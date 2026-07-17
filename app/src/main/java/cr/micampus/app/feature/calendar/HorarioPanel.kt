package cr.micampus.app.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

data class ScheduleSlot(val title: String, val day: DayOfWeek, val start: LocalTime, val end: LocalTime)

fun deriveScheduleSlots(events: List<CampusEvent>): List<ScheduleSlot> = events
    .asSequence()
    .filter { it.kind == EventKind.CLASS }
    .map { ScheduleSlot(it.title, it.start.dayOfWeek, it.start.toLocalTime(), it.end.toLocalTime()) }
    .distinct()
    .sortedWith(compareBy<ScheduleSlot> { it.start }.thenBy { it.title })
    .toList()

@Composable
fun HorarioPanel(events: List<CampusEvent>, use12h: Boolean, onSlotClick: (CampusEvent) -> Unit, modifier: Modifier = Modifier) {
    val slots = deriveScheduleSlots(events)
    if (slots.isEmpty()) {
        EmptyState("Sin horario de clases", "Importa tu carta al estudiante o crea clases para ver tu horario semanal.")
        return
    }
    val days = buildList {
        addAll(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        if (slots.any { it.day == DayOfWeek.SATURDAY }) add(DayOfWeek.SATURDAY)
        if (slots.any { it.day == DayOfWeek.SUNDAY }) add(DayOfWeek.SUNDAY)
    }
    val ranges = slots.map { it.start to it.end }.distinct().sortedBy { it.first }
    val formatter = timeFormatter(use12h)
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer,
        MaterialTheme.colorScheme.inversePrimary to MaterialTheme.colorScheme.onPrimary,
    )
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderCell("Horario", Modifier.weight(1f))
            days.forEach { HeaderCell(dayLabel(it), Modifier.weight(1f)) }
        }
        ranges.forEach { (start, end) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${start.format(formatter)}\n${end.format(formatter)}", modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).padding(4.dp), style = MaterialTheme.typography.labelMedium)
                days.forEach { day ->
                    val cellSlots = slots.filter { it.day == day && it.start == start && it.end == end }
                    if (cellSlots.isEmpty()) Surface(modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)) {}
                    else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        cellSlots.forEach { slot ->
                            val (container, content) = colors[(slot.title.hashCode() and Int.MAX_VALUE) % colors.size]
                            val event = matchingEvent(events, slot)
                            Surface(
                                onClick = { onSlotClick(event) },
                                color = container,
                                contentColor = content,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).semantics {
                                    contentDescription = "${slot.title}, ${dayLabel(day)}, de ${start.format(formatter)} a ${end.format(formatter)}"
                                },
                            ) {
                                Text(slot.title, modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.defaultMinSize(minHeight = 48.dp).padding(4.dp), style = MaterialTheme.typography.labelMedium)
}

private fun matchingEvent(events: List<CampusEvent>, slot: ScheduleSlot): CampusEvent {
    val matching = events.filter { it.kind == EventKind.CLASS && it.title == slot.title && it.start.dayOfWeek == slot.day && it.start.toLocalTime() == slot.start && it.end.toLocalTime() == slot.end }
    val now = LocalDateTime.now()
    return matching.firstOrNull { !it.start.isBefore(now) } ?: matching.maxBy { it.start }
}

private fun dayLabel(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lunes"
    DayOfWeek.TUESDAY -> "Martes"
    DayOfWeek.WEDNESDAY -> "Miércoles"
    DayOfWeek.THURSDAY -> "Jueves"
    DayOfWeek.FRIDAY -> "Viernes"
    DayOfWeek.SATURDAY -> "Sábado"
    DayOfWeek.SUNDAY -> "Domingo"
}
