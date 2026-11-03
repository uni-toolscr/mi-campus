package cr.micampus.app.feature.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import cr.micampus.app.core.designsystem.formatTimeRange
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.courseAccentPalette
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import kotlinx.coroutines.launch
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

/** Below this width the Horario shows one day per page; a week grid at a readable 120dp lane needs more room. */
private val HorarioDayViewMaxWidth = 600.dp

/** Mon–Fri always, plus Saturday/Sunday only when a class falls on them. */
fun scheduleDays(slots: List<ScheduleSlot>): List<DayOfWeek> = buildList {
    addAll(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
    if (slots.any { it.day == DayOfWeek.SATURDAY }) add(DayOfWeek.SATURDAY)
    if (slots.any { it.day == DayOfWeek.SUNDAY }) add(DayOfWeek.SUNDAY)
}

/** Today when it has a page, otherwise the next listed day (wrapping to the start of the week). */
fun initialScheduleDay(days: List<DayOfWeek>, today: DayOfWeek): DayOfWeek =
    days.firstOrNull { it == today } ?: days.firstOrNull { it.value > today.value } ?: days.first()

sealed interface SlotStatus {
    data object Ended : SlotStatus
    data object InProgress : SlotStatus
    data class StartsIn(val minutes: Long) : SlotStatus
}

sealed interface DayScheduleRow {
    data class Class(val slot: ScheduleSlot, val status: SlotStatus? = null) : DayScheduleRow
    data class FreeTime(val minutes: Long) : DayScheduleRow
    data class Now(val time: LocalTime) : DayScheduleRow
}

/**
 * One day's classes in start order with the free time between them. When [now] is given (the page is
 * today) classes get a live status and a now marker sits before the next class, unless one is in progress.
 */
fun dayScheduleRows(slots: List<ScheduleSlot>, day: DayOfWeek, now: LocalTime? = null): List<DayScheduleRow> {
    val daySlots = slots.filter { it.day == day }.sortedWith(compareBy<ScheduleSlot> { it.start }.thenBy { it.end }.thenBy { it.title })
    val inProgress = now != null && daySlots.any { !it.start.isAfter(now) && it.end.isAfter(now) }
    val nextIndex = if (now == null || inProgress) -1 else daySlots.indexOfFirst { it.start.isAfter(now) }
    var latestEnd: LocalTime? = null
    return buildList {
        daySlots.forEachIndexed { index, slot ->
            // Overlapping classes push latestEnd forward, so only real breaks become free time.
            latestEnd?.let { end -> if (slot.start.isAfter(end)) add(DayScheduleRow.FreeTime(Duration.between(end, slot.start).toMinutes())) }
            if (index == nextIndex && now != null) add(DayScheduleRow.Now(now))
            val status = when {
                now == null -> null
                !slot.end.isAfter(now) -> SlotStatus.Ended
                !slot.start.isAfter(now) -> SlotStatus.InProgress
                index == nextIndex -> SlotStatus.StartsIn(Duration.between(now, slot.start).toMinutes())
                else -> null
            }
            add(DayScheduleRow.Class(slot, status))
            latestEnd = latestEnd?.let { maxOf(it, slot.end) } ?: slot.end
        }
    }
}

/** "45 min", "2 h", "3 h 10 min". */
fun formatShortDuration(minutes: Long): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

fun scheduleSummary(slots: List<ScheduleSlot>): String {
    val courses = slots.map(ScheduleSlot::title).distinct().size
    val classes = slots.size
    return "$courses ${if (courses == 1) "curso" else "cursos"} · $classes ${if (classes == 1) "clase" else "clases"} por semana"
}

@Composable
fun HorarioPanel(
    events: List<CampusEvent>,
    use12h: Boolean,
    courseStyles: List<CourseStyle>,
    onOpenCourse: (String) -> Unit,
    onSaveStyle: (CourseStyle) -> Unit,
    showLocation: Boolean = true,
    shortDayLabels: Boolean = true,
    trailingContentClearance: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val slots = remember(events) { deriveScheduleSlots(events) }
    if (slots.isEmpty()) {
        Box(modifier) {
            EmptyState("Sin horario de clases", "Importa tu carta al estudiante o crea clases para ver tu horario semanal.")
        }
        return
    }
    val palette = coursePalette()
    val styles = courseStyles.associateBy(CourseStyle::courseKey)
    val colors = assignCourseColors(slots.map(ScheduleSlot::title), styles, palette.size)
    // Persist the auto-assigned color for any course without a saved style so it stays put even
    // when an alphabetically-earlier course is imported later (which would otherwise reshuffle the fill).
    val unstyled = colors.filterKeys { it !in styles }
    LaunchedEffect(unstyled) { unstyled.forEach { (key, index) -> onSaveStyle(CourseStyle(key, index)) } }
    BoxWithConstraints(modifier.testTag("calendar-schedule-content")) {
        if (maxWidth < HorarioDayViewMaxWidth) {
            HorarioDayPager(slots, use12h, palette, colors, showLocation, trailingContentClearance, onSlot = { onOpenCourse(it.title) })
        } else {
            HorarioWeekGrid(slots, use12h, palette, colors, showLocation, shortDayLabels, maxWidth, trailingContentClearance, onSlot = { onOpenCourse(it.title) })
        }
    }
}

@Composable
private fun HorarioDayPager(
    slots: List<ScheduleSlot>,
    use12h: Boolean,
    palette: List<Pair<Color, Color>>,
    colors: Map<String, Int>,
    showLocation: Boolean,
    trailingContentClearance: Dp,
    onSlot: (ScheduleSlot) -> Unit,
) {
    val now = rememberCurrentMinute()
    val today = now.dayOfWeek
    val days = remember(slots) { scheduleDays(slots) }
    val pagerState = rememberPagerState(initialPage = days.indexOf(initialScheduleDay(days, today))) { days.size }
    val scope = rememberCoroutineScope()
    val accents = courseAccentPalette()
    val dotsByDay = remember(slots, colors, accents) {
        days.associateWith { day -> slots.filter { it.day == day }.map { colors.getValue(it.title) }.distinct().map(accents::get) }
    }
    // A 12-hour time ("10:00 a. m.") needs a wider gutter than "10:00".
    val gutterWidth = if (use12h) 72.dp else 44.dp
    Column(Modifier.fillMaxSize()) {
        Text(
            scheduleSummary(slots),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        HorarioDayTabs(days, pagerState.currentPage, today, dotsByDay) { index -> scope.launch { pagerState.animateScrollToPage(index) } }
        HorizontalPager(pagerState, Modifier.fillMaxWidth().weight(1f), key = { days[it].value }) { page ->
            val day = days[page]
            val nowTime = if (day == today) now.toLocalTime() else null
            val rows = remember(slots, day, nowTime) { dayScheduleRows(slots, day, nowTime) }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    EmptyState("Sin clases", "No tienes clases el ${dayLabel(day).lowercase()}.")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = trailingContentClearance),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(rows, key = { index, row -> dayRowKey(index, row) }, contentType = { _, row -> row::class }) { _, row ->
                        when (row) {
                            is DayScheduleRow.Class -> {
                                val (container, onContainer) = palette[colors.getValue(row.slot.title)]
                                HorarioClassRow(row.slot, row.status, use12h, container, onContainer, showLocation, gutterWidth) { onSlot(row.slot) }
                            }
                            is DayScheduleRow.FreeTime -> FreeTimeRow(row.minutes, gutterWidth)
                            is DayScheduleRow.Now -> NowMarker(row.time, use12h, gutterWidth)
                        }
                    }
                }
            }
        }
    }
}

private fun dayRowKey(index: Int, row: DayScheduleRow): String = when (row) {
    is DayScheduleRow.Class -> "class-${row.slot.title}-${row.slot.start}-${row.slot.end}"
    is DayScheduleRow.FreeTime -> "free-$index"
    is DayScheduleRow.Now -> "now"
}

@Composable
private fun HorarioDayTabs(
    days: List<DayOfWeek>,
    selectedIndex: Int,
    today: DayOfWeek,
    dotsByDay: Map<DayOfWeek, List<Color>>,
    onSelect: (Int) -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            days.forEachIndexed { index, day ->
                val selected = index == selectedIndex
                val isToday = day == today
                val container by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    label = "horario-day-${day.name}",
                )
                val classCount = dotsByDay[day].orEmpty().size
                Surface(
                    selected = selected,
                    onClick = { onSelect(index) },
                    shape = RoundedCornerShape(16.dp),
                    color = container,
                    contentColor = when {
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("horario-day-tab-${day.name}")
                        .semantics {
                            role = Role.Tab
                            contentDescription = buildString {
                                append(dayLabel(day))
                                if (isToday) append(", hoy")
                                append(if (classCount == 0) ", sin clases" else ", $classCount ${if (classCount == 1) "curso" else "cursos"}")
                            }
                        },
                ) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            dayShort(day).removeSuffix("."),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isToday) FontWeight.Bold else null,
                            maxLines = 1,
                        )
                        Row(Modifier.padding(top = 4.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            dotsByDay[day].orEmpty().take(4).forEach { color ->
                                Box(Modifier.size(6.dp).background(color, CircleShape))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HorarioClassRow(
    slot: ScheduleSlot,
    status: SlotStatus?,
    use12h: Boolean,
    container: Color,
    onContainer: Color,
    showLocation: Boolean,
    gutterWidth: Dp,
    onClick: () -> Unit,
) {
    val formatter = timeFormatter(use12h)
    val statusLabel = when (status) {
        SlotStatus.InProgress -> "Ahora"
        is SlotStatus.StartsIn -> "en ${formatShortDuration(status.minutes)}"
        SlotStatus.Ended, null -> null
    }
    val courseCode = slot.event.courseCode?.takeIf(String::isNotBlank)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (status == SlotStatus.Ended) Modifier.alpha(0.6f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.width(gutterWidth).fillMaxHeight().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(slot.start.format(formatter), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Text(slot.end.format(formatter), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = container,
            contentColor = onContainer,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = listOfNotNull(
                    slot.title,
                    "${dayLabel(slot.day).lowercase()} de ${slot.start.format(formatter)} a ${slot.end.format(formatter)}",
                    statusLabel,
                    slot.event.location.takeIf { showLocation && it.isNotBlank() },
                ).joinToString(", ")
            },
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (courseCode != null || statusLabel != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        courseCode?.let { HorarioChip(it, container = onContainer, content = container) }
                        statusLabel?.let { HorarioChip(it, container = MaterialTheme.colorScheme.inverseSurface, content = MaterialTheme.colorScheme.inverseOnSurface) }
                    }
                }
                Text(
                    slot.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showLocation && slot.event.location.isNotBlank()) {
                    Text(slot.event.location, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HorarioChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun FreeTimeRow(minutes: Long, gutterWidth: Dp) {
    Row(
        Modifier.padding(start = gutterWidth + 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(2.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp)))
        Text("${formatShortDuration(minutes)} libre", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NowMarker(time: LocalTime, use12h: Boolean, gutterWidth: Dp) {
    val label = time.format(timeFormatter(use12h))
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Ahora, $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.End, modifier = Modifier.width(gutterWidth))
        Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun HorarioWeekGrid(
    slots: List<ScheduleSlot>,
    use12h: Boolean,
    palette: List<Pair<Color, Color>>,
    colors: Map<String, Int>,
    showLocation: Boolean,
    shortDayLabels: Boolean,
    maxWidth: Dp,
    trailingContentClearance: Dp,
    onSlot: (ScheduleSlot) -> Unit,
) {
    val formatter = timeFormatter(use12h)
    val axis = scheduleAxisBounds(slots)
    val days = scheduleDays(slots)
    val minuteHeight = 1.1.dp
    val headerHeight = 40.dp
    // Narrow time gutter (within the 46–52dp band) to reclaim space without cramping the columns.
    val gutterWidth = 46.dp
    // Keep each lane wide enough that times and locations aren't truncated; the week scrolls
    // horizontally when it can't fit, and fills the screen when it can.
    val minLaneWidth = 120.dp
    val axisMinutes = Duration.between(axis.start, axis.end).toMinutes().toInt().coerceAtLeast(0)
    val axisHeight = (minuteHeight * axisMinutes.toFloat()).coerceAtLeast(1.dp)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val laneCounts = remember(slots, days) {
        days.associateWith { day -> ((packLanes(slots.filter { it.day == day }).maxOfOrNull { it.second } ?: -1) + 1).coerceAtLeast(1) }
    }
    val totalLanes = laneCounts.values.sum().coerceAtLeast(1)
    // Distribute the available width across lanes, but never below the readable minimum.
    val laneWidth = maxOf(minLaneWidth, (maxWidth - gutterWidth) / totalLanes)
    Column(Modifier.verticalScroll(verticalScroll)) {
        Row(Modifier.horizontalScroll(horizontalScroll)) {
            Column(Modifier.width(gutterWidth)) {
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
                val laneCount = laneCounts.getValue(day)
                val columnWidth = laneWidth * laneCount
                Column(Modifier.width(columnWidth)) {
                    Box(Modifier.height(headerHeight).padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(dayHeaderLabel(day, shortDayLabels), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.height(axisHeight).fillMaxWidth()) {
                        lanes.forEach { (slot, lane) ->
                            val minutesFromStart = Duration.between(axis.start, slot.start).toMinutes().toInt().coerceAtLeast(0)
                            val duration = Duration.between(slot.start, slot.end).toMinutes().toInt().coerceAtLeast(0)
                            val height = (minuteHeight * duration.toFloat()).coerceAtLeast(48.dp)
                            val (container, onContainer) = palette[colors.getValue(slot.title)]
                            Surface(
                                onClick = { onSlot(slot) },
                                color = container,
                                contentColor = onContainer,
                                modifier = Modifier
                                    .offset(x = laneWidth * lane, y = minuteHeight * minutesFromStart.toFloat())
                                    .width(laneWidth)
                                    .height(height)
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                                    .semantics {
                                        contentDescription = "${slot.title}, ${dayLabel(slot.day).lowercase()} de ${slot.start.format(formatter)} a ${slot.end.format(formatter)}"
                                    },
                            ) {
                                Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(slot.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (height >= 58.dp) Text(formatTimeRange(slot.event.start, slot.event.end, use12h), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (showLocation && height >= 84.dp && slot.event.location.isNotBlank()) Text(slot.event.location, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(trailingContentClearance))
    }
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

private fun dayShort(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lun."
    DayOfWeek.TUESDAY -> "Mar."
    DayOfWeek.WEDNESDAY -> "Mié."
    DayOfWeek.THURSDAY -> "Jue."
    DayOfWeek.FRIDAY -> "Vie."
    DayOfWeek.SATURDAY -> "Sáb."
    DayOfWeek.SUNDAY -> "Dom."
}

private fun dayHeaderLabel(day: DayOfWeek, short: Boolean) = if (short) dayShort(day) else dayLabel(day)
