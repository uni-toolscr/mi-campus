package cr.micampus.app.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEE d 'de' MMMM yyyy", Locale.forLanguageTag("es-CR"))

@Composable
fun EventEditorDialog(
    event: CampusEvent,
    enabledInstitutions: List<Institution>,
    use12h: Boolean,
    onDismiss: () -> Unit,
    onSave: (CampusEvent) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    var title by remember(event.id) { mutableStateOf(event.title) }
    var institution by remember(event.id) { mutableStateOf(event.institution) }
    var kind by remember(event.id) { mutableStateOf(event.kind) }
    var start by remember(event.id) { mutableStateOf(event.start) }
    var end by remember(event.id) { mutableStateOf(event.end) }
    var location by remember(event.id) { mutableStateOf(event.location) }
    var notes by remember(event.id) { mutableStateOf(event.notes) }
    var notifyThirtyMinutesBefore by remember(event.id) { mutableStateOf(event.notifyThirtyMinutesBefore) }
    var institutionMenu by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    // Offer enabled institutions, plus the event's own institution if it was disabled after being set.
    val institutionOptions = (enabledInstitutions + event.institution).distinct()
    val rangeValid = end.isAfter(start)
    val notificationEligible = !event.allDay && kind in REMINDER_ELIGIBLE_KINDS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerHigh,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = { Text(if (onDelete == null) "Crear evento" else "Editar evento", color = colors.onSurface) },
        text = {
            LazyColumn(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Título") }, colors = editorTextFieldColors(), modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Descripción") }, minLines = 3, colors = editorTextFieldColors(), modifier = Modifier.fillMaxWidth()) }
                item {
                    DateField(
                        label = "Fecha de inicio",
                        value = start.toLocalDate(),
                        onChange = { date ->
                            val duration = Duration.between(start, end)
                            start = date.atTime(start.toLocalTime())
                            end = start.plus(duration)
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TimeField(
                            label = "Inicio",
                            value = start.toLocalTime(),
                            use12h = use12h,
                            onChange = { time ->
                                val duration = Duration.between(start, end)
                                start = start.toLocalDate().atTime(time)
                                end = start.plus(duration)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TimeField(
                            label = "Fin",
                            value = end.toLocalTime(),
                            use12h = use12h,
                            isError = !rangeValid,
                            onChange = { time -> end = end.toLocalDate().atTime(time) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { DateField(label = "Fecha de fin", value = end.toLocalDate(), onChange = { date -> end = date.atTime(end.toLocalTime()) }) }
                if (!rangeValid) item { Text("La hora de fin debe ser posterior al inicio", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(location, { location = it }, label = { Text("Lugar") }, colors = editorTextFieldColors(), modifier = Modifier.fillMaxWidth()) }
                item {
                    TextButton(onClick = { institutionMenu = true }) { Text("Institución: ${institution.name}", color = colors.primary) }
                    DropdownMenu(institutionMenu, { institutionMenu = false }, containerColor = colors.surfaceContainer) {
                        institutionOptions.forEach { value -> DropdownMenuItem({ Text(value.name) }, { institution = value; institutionMenu = false }) }
                    }
                }
                item { TextButton(onClick = { kindMenu = true }) { Text("Categoría: ${kindLabel(kind)}", color = colors.primary) }; DropdownMenu(kindMenu, { kindMenu = false }, containerColor = colors.surfaceContainer) { EventKind.values().forEach { value -> DropdownMenuItem({ Text(kindLabel(value)) }, { kind = value; kindMenu = false }) } } }
                if (notificationEligible) item {
                    ReminderSwitchRow(
                        checked = notifyThirtyMinutesBefore,
                        onCheckedChange = { notifyThirtyMinutesBefore = it },
                    )
                }
                if (onDelete != null) item { TextButton(onClick = onDelete) { Text("Eliminar", color = colors.error) } }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        event.copy(
                            title = title.trim(),
                            institution = institution,
                            kind = kind,
                            start = start,
                            end = end,
                            location = location.trim(),
                            notes = notes.trim(),
                            notifyThirtyMinutesBefore = notifyThirtyMinutesBefore && notificationEligible,
                        ),
                    )
                },
                enabled = title.isNotBlank() && rangeValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
                ),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = colors.primary) } },
    )
}

@Composable
private fun ReminderSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Notificación 30 min antes"
                stateDescription = if (checked) "Activada" else "Desactivada"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Notificación 30 min antes", modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private val REMINDER_ELIGIBLE_KINDS = setOf(EventKind.EXAM, EventKind.QUIZ, EventKind.TAREA, EventKind.ACTIVITY)

/** Read-only text field that opens a Material 3 date picker; value shown in Spanish (es-CR). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    PickerField(label, value.format(dateFormatter).replaceFirstChar { it.titlecase() }, onClick = { open = true }, modifier = modifier)
    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = value.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = colors.surfaceContainerHigh,
                titleContentColor = colors.onSurface,
                headlineContentColor = colors.onSurface,
                weekdayContentColor = colors.onSurfaceVariant,
                subheadContentColor = colors.onSurfaceVariant,
                navigationContentColor = colors.onSurfaceVariant,
                yearContentColor = colors.onSurfaceVariant,
                currentYearContentColor = colors.primary,
                selectedYearContentColor = colors.onPrimary,
                selectedYearContainerColor = colors.primary,
                dayContentColor = colors.onSurface,
                selectedDayContentColor = colors.onPrimary,
                selectedDayContainerColor = colors.primary,
                todayContentColor = colors.primary,
                todayDateBorderColor = colors.primary,
            ),
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { open = false }, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("Cancelar") } },
        ) {
            DatePicker(
                state = pickerState,
                colors = androidx.compose.material3.DatePickerDefaults.colors(
                    containerColor = colors.surfaceContainerHigh,
                    titleContentColor = colors.onSurface,
                    headlineContentColor = colors.onSurface,
                    weekdayContentColor = colors.onSurfaceVariant,
                    subheadContentColor = colors.onSurfaceVariant,
                    navigationContentColor = colors.onSurfaceVariant,
                    yearContentColor = colors.onSurfaceVariant,
                    currentYearContentColor = colors.primary,
                    selectedYearContentColor = colors.onPrimary,
                    selectedYearContainerColor = colors.primary,
                    dayContentColor = colors.onSurface,
                    selectedDayContentColor = colors.onPrimary,
                    selectedDayContainerColor = colors.primary,
                    todayContentColor = colors.primary,
                    todayDateBorderColor = colors.primary,
                ),
            )
        }
    }
}

/** Read-only text field that opens a Material 3 time picker honoring the 12h/24h preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, value: LocalTime, use12h: Boolean, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    PickerField(label, value.format(timeFormatter(use12h)), onClick = { open = true }, modifier = modifier, isError = isError)
    if (open) {
        val pickerState = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = !use12h)
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = colors.surfaceContainerHigh,
            titleContentColor = colors.onSurface,
            textContentColor = colors.onSurface,
            title = { Text(label, color = colors.onSurface) },
            text = {
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    TimePicker(
                        state = pickerState,
                        colors = androidx.compose.material3.TimePickerDefaults.colors(
                            clockDialColor = colors.surfaceVariant,
                            clockDialSelectedContentColor = colors.onPrimary,
                            clockDialUnselectedContentColor = colors.onSurface,
                            selectorColor = colors.primary,
                            containerColor = colors.surfaceContainerHigh,
                            periodSelectorBorderColor = colors.outline,
                            periodSelectorSelectedContainerColor = colors.secondaryContainer,
                            periodSelectorUnselectedContainerColor = colors.surfaceContainerHigh,
                            periodSelectorSelectedContentColor = colors.onSecondaryContainer,
                            periodSelectorUnselectedContentColor = colors.onSurfaceVariant,
                            timeSelectorSelectedContainerColor = colors.primaryContainer,
                            timeSelectorUnselectedContainerColor = colors.surfaceContainerHighest,
                            timeSelectorSelectedContentColor = colors.onPrimaryContainer,
                            timeSelectorUnselectedContentColor = colors.onSurface,
                        ),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(pickerState.hour, pickerState.minute)); open = false }, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("Aceptar") } },
            dismissButton = { TextButton(onClick = { open = false }, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text("Cancelar") } },
        )
    }
}

/**
 * OutlinedTextField styled as a tappable, read-only value. The transparent overlay captures taps
 * because a disabled/read-only text field swallows click events on its own.
 */
@Composable
private fun PickerField(label: String, valueText: String, onClick: () -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    Box(modifier) {
        OutlinedTextField(
            value = valueText,
            onValueChange = {},
            readOnly = true,
            isError = isError,
            label = { Text(label) },
            colors = editorTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Cambiar $label", onClick = onClick)
                .semantics { contentDescription = "$label: $valueText" },
        )
    }
}

@Composable
private fun editorTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

internal fun newEvent(defaultInstitution: Institution): CampusEvent {
    val start = LocalDateTime.now().withSecond(0).withNano(0).plusHours(1)
    return CampusEvent("manual-${System.currentTimeMillis()}", "", defaultInstitution, EventKind.ACTIVITY, start, start.plusHours(1))
}

internal fun kindLabel(kind: EventKind) = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.TAREA -> "Tarea"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}
