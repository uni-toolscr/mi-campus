package cr.micampus.app.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.feature.calendar.EventEditorDialog
import cr.micampus.app.feature.calendar.rememberCurrentMinute
import java.time.format.DateTimeFormatter
import java.util.Locale

private val evaluationFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-CR"))

/**
 * A course at a glance: its weekly blocks, what's coming up for it, and its color. Opened from a
 * class in Horario, Semana or Inicio; evaluations open their own page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CourseSheet(
    courseKey: String,
    state: DetailUiState,
    viewModel: DetailViewModel,
    onDismiss: () -> Unit,
    onOpenEvent: (CampusEvent) -> Unit,
) {
    val now = rememberCurrentMinute()
    val course = remember(courseKey, state.events, now) { courseDetail(courseKey, state.events, now) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editing by remember { mutableStateOf<CampusEvent?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (course == null) {
        // The course's last session was deleted; an empty list only means events are still loading.
        if (state.events.isNotEmpty()) LaunchedEffect(courseKey) { onDismiss() }
        return
    }
    val use12h = state.use12hClock
    val palette = coursePalette()
    val colors = rememberCourseColor(courseKey, state)
    val selectedIndex = colors?.let(palette::indexOf)

    editing?.let { session ->
        EventEditorDialog(
            session,
            enabledInstitutions = state.enabledInstitutions,
            use12h = use12h,
            onDismiss = { editing = null },
            onSave = { viewModel.save(it); editing = null },
            onDelete = { viewModel.delete(session); editing = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar ${course.key}") },
            text = { Text("Se eliminarán todas las sesiones de este curso y sus recordatorios. Sus exámenes y tareas se conservan.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteCourse(course.key); onDismiss() }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .testTag("course-sheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors?.first ?: MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = colors?.second ?: MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(courseMonogram(course.key, course.code), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(course.key, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(
                        listOfNotNull(course.code, course.institution.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SheetSection("Horario semanal")
            course.slots.forEachIndexed { index, slot ->
                SegmentedListItem(
                    colors = groupedListColors(),
                    shapes = ListItemDefaults.segmentedShapes(index, course.slots.size),
                    trailingContent = slot.event.location.takeIf(String::isNotBlank)?.let { place -> { Text(place, maxLines = 1) } },
                    supportingContent = { Text("${slot.start.format(timeFormatter(use12h))} – ${slot.end.format(timeFormatter(use12h))}") },
                    modifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap),
                ) { Text(dayName(slot.day)) }
            }

            SheetSection("Próximas evaluaciones")
            if (course.evaluations.isEmpty()) {
                Text("Nada pendiente para este curso.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            val evaluations = course.evaluations.take(5)
            evaluations.forEachIndexed { index, event ->
                SegmentedListItem(
                    onClick = { onOpenEvent(event) },
                    colors = groupedListColors(),
                    shapes = ListItemDefaults.segmentedShapes(index, evaluations.size),
                    leadingContent = { Box(Modifier.width(6.dp).height(36.dp).background(colors?.second ?: MaterialTheme.colorScheme.outline, CircleShape)) },
                    supportingContent = { Text("${event.start.format(evaluationFormatter)} · ${event.start.format(timeFormatter(use12h))}") },
                    trailingContent = { Text(eventKindName(event.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap),
                ) { Text(event.title, maxLines = 2) }
            }

            SheetSection("Color")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEachIndexed { index, (container, onContainer) ->
                    val selected = index == selectedIndex
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(container, CircleShape)
                            .then(if (selected) Modifier.border(2.dp, onContainer, CircleShape) else Modifier)
                            .clickable(role = Role.RadioButton) { viewModel.saveCourseStyle(CourseStyle(course.key, index)) }
                            .semantics {
                                this.selected = selected
                                contentDescription = "Color ${index + 1}"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = onContainer)
                    }
                }
            }

            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                course.nextSession?.let { session ->
                    FilledTonalButton(onClick = { editing = session }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Editar próxima sesión")
                    }
                }
                OutlinedButton(onClick = { confirmDelete = true }, shapes = ButtonDefaults.shapes()) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}
