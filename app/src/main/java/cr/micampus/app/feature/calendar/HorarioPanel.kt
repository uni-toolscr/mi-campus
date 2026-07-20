package cr.micampus.app.feature.calendar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

data class ScheduleSlot(
    val event: CampusEvent,
    val title: String,
    val day: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
)

data class ScheduleAxisBounds(val start: LocalTime, val end: LocalTime)

fun deriveScheduleSlots(events: List<CampusEvent>, now: LocalDateTime = LocalDateTime.now()): List<ScheduleSlot> =
    events.asSequence()
        .filter { it.kind == EventKind.CLASS && it.end.isAfter(it.start) && it.title.trim().isNotEmpty() && it.end.toLocalTime() > it.start.toLocalTime() }
        .groupBy { event -> ScheduleKey(event.title.trim(), event.start.dayOfWeek, event.start.toLocalTime(), event.end.toLocalTime()) }
        .map { (key, matchingEvents) ->
            val representative = matchingEvents.filter { !it.start.isBefore(now) }.minByOrNull(CampusEvent::start)
                ?: matchingEvents.maxBy(CampusEvent::start)
            ScheduleSlot(representative, key.title, key.day, key.start, key.end)
        }
        .sortedWith(compareBy<ScheduleSlot> { it.start }.thenBy { it.title }.thenBy { it.day.value })
        .toList()

fun scheduleAxisBounds(slots: List<ScheduleSlot>): ScheduleAxisBounds {
    if (slots.isEmpty()) return ScheduleAxisBounds(LocalTime.of(7, 0), LocalTime.of(21, 0))
    val earliest = slots.minOf(ScheduleSlot::start).withMinute(0).withSecond(0).withNano(0)
    val latestSlotEnd = slots.maxOf(ScheduleSlot::end)
    val latest = when {
        latestSlotEnd.minute == 0 && latestSlotEnd.second == 0 && latestSlotEnd.nano == 0 -> latestSlotEnd
        // Rounding a 23:xx end up to the next hour would wrap past midnight and invert the axis; clamp instead.
        latestSlotEnd.hour >= 23 -> LocalTime.of(23, 59)
        else -> latestSlotEnd.plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }
    return ScheduleAxisBounds(earliest, latest)
}

fun packLanes(daySlots: List<ScheduleSlot>): List<Pair<ScheduleSlot, Int>> {
    val laneEnds = mutableListOf<LocalTime>()
    return daySlots.sortedWith(compareBy<ScheduleSlot> { it.start }.thenBy { it.end }.thenBy { it.title }).map { slot ->
        val lane = laneEnds.indexOfFirst { end -> !end.isAfter(slot.start) }.let { if (it == -1) laneEnds.size else it }
        if (lane == laneEnds.size) laneEnds += slot.end else laneEnds[lane] = slot.end
        slot to lane
    }
}

@Composable
fun HorarioPanel(
    events: List<CampusEvent>,
    use12h: Boolean,
    courseStyles: List<CourseStyle>,
    onSlotClick: (CampusEvent) -> Unit,
    onSaveStyle: (CourseStyle) -> Unit,
    onDeleteSeries: (ScheduleSlot) -> Unit,
    trailingContentClearance: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val slots = remember(events) { deriveScheduleSlots(events) }
    if (slots.isEmpty()) {
        Box(modifier) {
            EmptyState("Sin horario de clases", "Importa tu carta al estudiante o crea clases para ver tu horario semanal.")
        }
        return
    }
    val formatter = timeFormatter(use12h)
    val palette = coursePalette()
    val styles = courseStyles.associateBy(CourseStyle::courseKey)
    val colors = assignCourseColors(slots.map(ScheduleSlot::title), styles, palette.size)
    // Persist the auto-assigned color for any course without a saved style so it stays put even
    // when an alphabetically-earlier course is imported later (which would otherwise reshuffle the fill).
    val unstyled = colors.filterKeys { it !in styles }
    LaunchedEffect(unstyled) { unstyled.forEach { (key, index) -> onSaveStyle(CourseStyle(key, index, null)) } }
    var styleSlot by remember { mutableStateOf<ScheduleSlot?>(null) }
    val axis = scheduleAxisBounds(slots)
    val days = buildList {
        addAll(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        if (slots.any { it.day == DayOfWeek.SATURDAY }) add(DayOfWeek.SATURDAY)
        if (slots.any { it.day == DayOfWeek.SUNDAY }) add(DayOfWeek.SUNDAY)
    }
    val minuteHeight = 1.1.dp
    val headerHeight = 40.dp
    val axisMinutes = Duration.between(axis.start, axis.end).toMinutes().toInt().coerceAtLeast(0)
    val axisHeight = (minuteHeight * axisMinutes.toFloat()).coerceAtLeast(1.dp)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    Column(modifier.testTag("calendar-schedule-content").verticalScroll(verticalScroll)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(horizontalScroll).widthIn(min = (48 + days.size * 96).dp)) {
            Column(Modifier.width(48.dp)) {
                // Offset the hour gutter by the day-header height so hour labels line up with the blocks below.
                Spacer(Modifier.height(headerHeight))
                (0..axisMinutes / 60).forEach { hour ->
                    val time = axis.start.plusHours(hour.toLong())
                    Text(time.format(formatter), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(minuteHeight * 60f))
                }
            }
            days.forEach { day ->
                val daySlots = slots.filter { it.day == day }
                val lanes = packLanes(daySlots)
                val laneCount = ((lanes.maxOfOrNull { it.second } ?: -1) + 1).coerceAtLeast(1)
                // Grow the column so each lane keeps a ≥72dp (well above the 48dp minimum) touch target.
                val columnWidth = maxOf(96.dp, (72 * laneCount).dp)
                Column(Modifier.width(columnWidth)) {
                    Box(Modifier.height(headerHeight).padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(dayLabel(day), style = MaterialTheme.typography.labelLarge)
                    }
                    BoxWithConstraints(Modifier.height(axisHeight).fillMaxWidth()) {
                        lanes.forEach { (slot, lane) ->
                            val minutesFromStart = Duration.between(axis.start, slot.start).toMinutes().toInt().coerceAtLeast(0)
                            val duration = Duration.between(slot.start, slot.end).toMinutes().toInt().coerceAtLeast(0)
                            val height = (minuteHeight * duration.toFloat()).coerceAtLeast(48.dp)
                            val (container, onContainer) = palette[colors.getValue(slot.title)]
                            Surface(
                                onClick = { styleSlot = slot },
                                color = container,
                                contentColor = onContainer,
                                modifier = Modifier
                                    .offset(x = maxWidth / laneCount * lane, y = minuteHeight * minutesFromStart.toFloat())
                                    .width(maxWidth / laneCount)
                                    .height(height)
                                    .padding(2.dp)
                                    .semantics {
                                        contentDescription = "${slot.title}, ${dayLabel(slot.day).lowercase()} de ${slot.start.format(formatter)} a ${slot.end.format(formatter)}"
                                    },
                            ) {
                                Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(listOfNotNull(styles[slot.title]?.emoji?.takeIf(String::isNotBlank), slot.title).joinToString(" "), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (height >= 82.dp) Text("${slot.start.format(formatter)}–${slot.end.format(formatter)}", style = MaterialTheme.typography.labelSmall)
                                    if (height >= 112.dp && slot.event.location.isNotBlank()) Text(slot.event.location, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(trailingContentClearance))
    }
    styleSlot?.let { slot ->
        CourseStyleDialog(
            slot = slot,
            existing = styles[slot.title],
            initialColorIndex = colors.getValue(slot.title),
            palette = palette,
            onDismiss = { styleSlot = null },
            onSave = { onSaveStyle(it); styleSlot = null },
            onEditEvent = { styleSlot = null; onSlotClick(slot.event) },
            onDeleteSeries = { styleSlot = null; onDeleteSeries(slot) },
        )
    }
}

@Composable
private fun CourseStyleDialog(
    slot: ScheduleSlot,
    existing: CourseStyle?,
    initialColorIndex: Int,
    palette: List<Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>>,
    onDismiss: () -> Unit,
    onSave: (CourseStyle) -> Unit,
    onEditEvent: () -> Unit,
    onDeleteSeries: () -> Unit,
) {
    var emoji by remember(slot.title) { mutableStateOf(existing?.emoji.orEmpty()) }
    var colorIndex by remember(slot.title, existing?.colorIndex, initialColorIndex) { mutableIntStateOf(Math.floorMod(existing?.colorIndex ?: initialColorIndex, palette.size)) }
    var confirmDelete by remember(slot.title) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar clase") },
            text = { Text("Se eliminarán todas las sesiones de ${slot.title} de este bloque. Esta acción no se puede deshacer.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteSeries() }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Estilo de ${slot.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(2) },
                    label = { Text("Emoji") },
                    singleLine = true,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEachIndexed { index, (container, onContainer) ->
                        Surface(
                            onClick = { colorIndex = index },
                            color = container,
                            contentColor = onContainer,
                            modifier = Modifier.width(48.dp).height(48.dp),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { if (colorIndex == index) Text("✓") } }
                    }
                }
                TextButton(onClick = onEditEvent) { Text("Editar evento…") }
                TextButton(onClick = { confirmDelete = true }) { Text("Eliminar clase…", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(CourseStyle(slot.title, colorIndex, emoji.trim().ifBlank { null })) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private data class ScheduleKey(val title: String, val day: DayOfWeek, val start: LocalTime, val end: LocalTime)

private fun dayLabel(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lunes"
    DayOfWeek.TUESDAY -> "Martes"
    DayOfWeek.WEDNESDAY -> "Miércoles"
    DayOfWeek.THURSDAY -> "Jueves"
    DayOfWeek.FRIDAY -> "Viernes"
    DayOfWeek.SATURDAY -> "Sábado"
    DayOfWeek.SUNDAY -> "Domingo"
}
