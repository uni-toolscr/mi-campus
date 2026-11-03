package cr.micampus.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.formatTimeRange
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.isExpired
import cr.micampus.app.feature.calendar.EventEditorDialog
import cr.micampus.app.feature.calendar.ScheduleSlot
import cr.micampus.app.feature.calendar.deriveScheduleSlots
import cr.micampus.app.feature.calendar.rememberCurrentMinute
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val detailDayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-CR"))

/**
 * Full page for one event: what it is, when and where at a glance, its reminders, and (for
 * evaluations) the classes left before it. Editing is a secondary action, not the first thing shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventDetailScreen(
    eventId: String,
    state: DetailUiState,
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val event = state.events.firstOrNull { it.id == eventId }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (event == null) {
        // Deleted here or elsewhere; an empty list only means the events haven't loaded yet.
        if (state.events.isNotEmpty()) LaunchedEffect(eventId) { onBack() }
        Box(Modifier.fillMaxSize())
        return
    }
    val now = rememberCurrentMinute()
    val use12h = state.use12hClock
    val courseKey = remember(event, state.events) { courseKeyFor(event, state.events) }
    val colors = rememberCourseColor(courseKey, state)
    val classesLeft = remember(event, state.events, now) { classesBefore(event, state.events, now) }
    val course = remember(courseKey, state.events, now) { courseKey?.let { courseDetail(it, state.events, now) } }

    if (editing) {
        EventEditorDialog(
            event,
            enabledInstitutions = state.enabledInstitutions,
            use12h = use12h,
            onDismiss = { editing = false },
            onSave = { viewModel.save(it); editing = false },
            onDelete = { editing = false; viewModel.delete(event); onBack() },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar evento") },
            text = { Text("Se eliminará ${event.title} y sus recordatorios.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(event); onBack() }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    Column(Modifier.fillMaxSize().safeHorizontalInsets().testTag("event-detail")) {
        DetailTopBar(onBack, onEdit = { editing = true }, onDelete = { confirmDelete = true })
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "hero") {
                EventHero(event, course?.let { listOfNotNull(it.key, it.code).joinToString(" · ") }, colors, use12h, now) {
                    courseKey?.let(onOpenCourse)
                }
            }
            if (event.kind == EventKind.TAREA) {
                item(key = "done") {
                    Button(
                        onClick = { viewModel.setCompleted(event, !event.completed) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp),
                    ) {
                        Icon(if (event.completed) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.TaskAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (event.completed) "Marcar como pendiente" else "Marcar como hecha", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            event.externalUrl?.takeIf(String::isNotBlank)?.let { url ->
                item(key = "external") {
                    OutlinedButton(onClick = { onOpenUrl(url) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Abrir en Aula Virtual")
                    }
                }
            }
            if (event.notes.isNotBlank()) {
                section("Notas")
                item(key = "notes") {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(event.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            if (!event.completed && !event.isExpired(now)) {
                section("Recordatorios")
                reminderRows(event, state.remindersEnabled, onOpenSettings) { viewModel.setThirtyMinuteReminder(event, it) }
            }
            if (classesLeft.isNotEmpty() && courseKey != null) {
                section(beforeEventHeading(event.kind))
                val shown = classesLeft.take(4)
                itemsIndexed(shown, key = { _, it -> "before-${it.id}" }) { index, session ->
                    SegmentedListItem(
                        onClick = { onOpenCourse(courseKey) },
                        colors = groupedListColors(),
                        shapes = ListItemDefaults.segmentedShapes(index, shown.size + if (classesLeft.size > shown.size) 1 else 0),
                        leadingContent = { Box(Modifier.width(6.dp).height(36.dp).background(colors?.second ?: MaterialTheme.colorScheme.outline, CircleShape)) },
                        supportingContent = { Text("${session.start.format(detailDayFormatter)} · ${formatTimeRange(session.start, session.end, use12h)}") },
                    ) { Text("Clase · ${session.title}") }
                }
                if (classesLeft.size > shown.size) {
                    item(key = "before-more") {
                        SegmentedListItem(
                            onClick = { onOpenCourse(courseKey) },
                            colors = groupedListColors(),
                            shapes = ListItemDefaults.segmentedShapes(shown.size, shown.size + 1),
                        ) { Text("${classesLeft.size - shown.size} clases más", color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item(key = "actions") {
                FilledTonalButton(onClick = { editing = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Editar")
                }
            }
        }
    }
}

@Composable
private fun DetailTopBar(onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Volver") }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Más opciones") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Editar") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EventHero(
    event: CampusEvent,
    courseLine: String?,
    colors: Pair<Color, Color>?,
    use12h: Boolean,
    now: LocalDateTime,
    onCourse: () -> Unit,
) {
    val container = colors?.first ?: MaterialTheme.colorScheme.secondaryContainer
    val content = colors?.second ?: MaterialTheme.colorScheme.onSecondaryContainer
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val decoration = content.copy(alpha = 0.12f)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = content, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier
                .drawBehind {
                    val side = 170.dp.toPx()
                    translate(size.width - side * 0.6f, size.height - side * 0.6f) {
                        drawOutline(cookie.createOutline(Size(side, side), layoutDirection, this), decoration)
                    }
                }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = content, contentColor = container) {
                Text(eventStatusLabel(event, now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            Text(event.title, style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
            if (courseLine != null) {
                TextButton(onClick = onCourse, contentPadding = PaddingValues(0.dp)) {
                    Text(courseLine, style = MaterialTheme.typography.titleMedium, color = content)
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeroFact("Día", event.start.format(detailDayFormatter).replace(".", "").replaceFirstChar(Char::titlecase))
                HeroFact(
                    "Hora",
                    when {
                        event.allDay -> "Todo el día"
                        event.start == event.end -> event.start.format(timeFormatter(use12h))
                        else -> formatTimeRange(event.start, event.end, use12h)
                    },
                )
                if (event.location.isNotBlank()) HeroFact("Lugar", event.location, Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun HeroFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.reminderRows(
    event: CampusEvent,
    remindersEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onThirtyMinutes: (Boolean) -> Unit,
) {
    val automatic = automaticReminderLabel(event.kind)
    if (!remindersEnabled || automatic == null) {
        item(key = "reminders-off") {
            SegmentedListItem(
                onClick = onOpenSettings,
                colors = groupedListColors(),
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                leadingContent = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null) },
                supportingContent = { Text(if (automatic == null) "Este tipo de evento no tiene avisos." else "Actívalos en Ajustes para recibir avisos.") },
            ) { Text("Sin recordatorios") }
        }
        return
    }
    val showThirty = !event.allDay
    val count = if (showThirty) 2 else 1
    item(key = "reminder-auto") {
        SegmentedListItem(
            colors = groupedListColors(),
            shapes = ListItemDefaults.segmentedShapes(0, count),
            supportingContent = { Text("Aviso automático") },
        ) { Text(automatic.replaceFirstChar(Char::titlecase)) }
    }
    if (showThirty) {
        item(key = "reminder-30") {
            SegmentedListItem(
                colors = groupedListColors(),
                shapes = ListItemDefaults.segmentedShapes(1, count),
                trailingContent = { Switch(checked = event.notifyThirtyMinutesBefore, onCheckedChange = onThirtyMinutes) },
            ) { Text("30 min antes") }
        }
    }
}

/** The course's (container, onContainer) pair from the Horario palette, or null for events without a course. */
@Composable
internal fun rememberCourseColor(courseKey: String?, state: DetailUiState): Pair<Color, Color>? {
    val palette = coursePalette()
    return remember(courseKey, state.events, state.courseStyles, palette) {
        courseKey ?: return@remember null
        val keys = deriveScheduleSlots(state.events).map(ScheduleSlot::title)
        assignCourseColors(keys, state.courseStyles.associateBy(CourseStyle::courseKey), palette.size)[courseKey]?.let(palette::get)
    }
}
