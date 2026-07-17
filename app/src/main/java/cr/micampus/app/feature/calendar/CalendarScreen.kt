package cr.micampus.app.feature.calendar

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.home.EventCard
import cr.micampus.app.platform.calendar.CalendarChoice
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-CR"))

@Composable
fun CalendarScreen(state: CalendarUiState, viewModel: CalendarViewModel, expanded: Boolean) {
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<CampusEvent?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarChoice>>(emptyList()) }
    var showCalendars by remember { mutableStateOf(false) }
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            calendars = viewModel.writableCalendars()
            showCalendars = true
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    if (state.confirmationPending) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text("Actualizar eventos externos") },
            text = { Text("Algunos eventos cambiaron desde la última exportación. ¿Quieres actualizarlos? Nunca se eliminarán eventos del calendario externo.") },
            confirmButton = { TextButton(onClick = { selectedCalendarId?.let { viewModel.exportAll(it, true) } }) { Text("Actualizar") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation) { Text("Cancelar") } },
        )
    }
    if (showCalendars) {
        AlertDialog(
            onDismissRequest = { showCalendars = false },
            title = { Text("Exportar a calendario") },
            text = {
                Column {
                    if (calendars.isEmpty()) Text("No hay calendarios con permiso de escritura.")
                    calendars.forEach { calendar ->
                        TextButton(onClick = { selectedCalendarId = calendar.id; showCalendars = false; viewModel.exportAll(calendar.id, false) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) { Text(calendar.name); Text(calendar.account, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCalendars = false }) { Text("Cerrar") } },
        )
    }
    editor?.let { event ->
        EventEditorDialog(
            event,
            enabledInstitutions = state.enabledInstitutions,
            onDismiss = { editor = null },
            onSave = { viewModel.save(it); editor = null },
            onDelete = if (state.events.any { it.id == event.id }) ({ viewModel.delete(event); editor = null }) else null,
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editor = newEvent(state.defaultInstitution) }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Text("Crear evento")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Column { Text("Calendario", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("Eventos confirmados", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            CalendarControls(state, viewModel)
            if (expanded) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    MonthPanel(state.month, state.events, viewModel::setMonth, Modifier.weight(0.8f).fillMaxHeight())
                    if (state.presentation == CalendarPresentation.HORARIO) HorarioPanel(state.events, state.use12hClock, { editor = it }, Modifier.weight(1.2f)) else Agenda(state, onEvent = { editor = it }, modifier = Modifier.weight(1.2f))
                }
            } else if (state.presentation == CalendarPresentation.MONTH) {
                MonthPanel(state.month, state.events, viewModel::setMonth, Modifier.weight(1f))
            } else if (state.presentation == CalendarPresentation.HORARIO) HorarioPanel(state.events, state.use12hClock, { editor = it }, Modifier.weight(1f)) else Agenda(state, onEvent = { editor = it }, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null)
                Text(" Exportar eventos confirmados")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarControls(state: CalendarUiState, viewModel: CalendarViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CalendarPresentation.values().forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.presentation == mode,
                    onClick = { viewModel.setPresentation(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CalendarPresentation.values().size),
                    label = { Text(when (mode) { CalendarPresentation.MONTH -> "Mes"; CalendarPresentation.AGENDA -> "Agenda"; CalendarPresentation.HORARIO -> "Horario" }) },
                )
            }
        }
        LazyRowFilters(state, viewModel)
        OutlinedTextField(value = state.filters.courseQuery, onValueChange = viewModel::setCourseQuery, label = { Text("Filtrar por curso o texto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LazyRowFilters(state: CalendarUiState, viewModel: CalendarViewModel) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Both institutions are only worth filtering between when the student actually enabled both.
        if (state.enabledInstitutions.size > 1) {
            item { FilterChip(selected = state.filters.institution == null, onClick = { viewModel.setInstitution(null) }, label = { Text("Todas") }) }
            items(Institution.values().toList()) { institution -> FilterChip(selected = state.filters.institution == institution, onClick = { viewModel.setInstitution(institution) }, label = { Text(institution.name) }) }
        }
        item { FilterChip(selected = state.filters.kind == null, onClick = { viewModel.setKind(null) }, label = { Text("Categorías") }) }
        items(EventKind.values().toList()) { kind -> FilterChip(selected = state.filters.kind == kind, onClick = { viewModel.setKind(kind) }, label = { Text(kindLabel(kind)) }) }
    }
}

@Composable
private fun Agenda(state: CalendarUiState, onEvent: (CampusEvent) -> Unit, modifier: Modifier = Modifier) {
    when {
        state.loading -> LoadingState()
        state.events.isEmpty() -> EmptyState("No hay eventos", "Ajusta los filtros, crea un evento o importa un PDF.")
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.events, key = CampusEvent::id) { EventCard(it, onClick = { onEvent(it) }, use12h = state.use12hClock) }
        }
    }
}

@Composable
private fun MonthPanel(month: YearMonth, events: List<CampusEvent>, onMonth: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    val today = java.time.LocalDate.now()
    Column(modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Mes anterior")
            }
            Text(month.atDay(1).format(monthFormatter).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Mes siguiente")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach { label ->
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
        }
        val leadingEmptyDays = month.atDay(1).dayOfWeek.value - 1
        val cells = List<Int?>(leadingEmptyDays) { null } + (1..month.lengthOfMonth()).map { it }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val date = day?.let { month.atDay(it) }
                    val count = date?.let { value -> events.count { it.start.toLocalDate() == value } } ?: 0
                    val isToday = date == today
                    Column(Modifier.weight(1f).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isToday) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(day?.toString().orEmpty(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            Text(day?.toString().orEmpty())
                        }
                        if (count > 0) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(6.dp)) {}
                        }
                    }
                }
                repeat(7 - week.size) { Column(Modifier.weight(1f)) {} }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditorDialog(
    event: CampusEvent,
    enabledInstitutions: List<Institution>,
    onDismiss: () -> Unit,
    onSave: (CampusEvent) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(event.id) { mutableStateOf(event.title) }
    var institution by remember(event.id) { mutableStateOf(event.institution) }
    var kind by remember(event.id) { mutableStateOf(event.kind) }
    var start by remember(event.id) { mutableStateOf(event.start.toString()) }
    var end by remember(event.id) { mutableStateOf(event.end.toString()) }
    var location by remember(event.id) { mutableStateOf(event.location) }
    var notes by remember(event.id) { mutableStateOf(event.notes) }
    var institutionMenu by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    // Offer enabled institutions, plus the event's own institution if it was disabled after being set.
    val institutionOptions = (enabledInstitutions + event.institution).distinct()
    val parsedStart = runCatching { LocalDateTime.parse(start) }.getOrNull()
    val parsedEnd = runCatching { LocalDateTime.parse(end) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "Crear evento" else "Editar evento") },
        text = {
            LazyColumn(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    TextButton(onClick = { institutionMenu = true }) { Text("Institución: ${institution.name}") }
                    DropdownMenu(institutionMenu, { institutionMenu = false }) {
                        institutionOptions.forEach { value -> DropdownMenuItem({ Text(value.name) }, { institution = value; institutionMenu = false }) }
                    }
                }
                item { TextButton(onClick = { kindMenu = true }) { Text("Categoría: ${kindLabel(kind)}") }; DropdownMenu(kindMenu, { kindMenu = false }) { EventKind.values().forEach { value -> DropdownMenuItem({ Text(kindLabel(value)) }, { kind = value; kindMenu = false }) } } }
                item { OutlinedTextField(start, { start = it }, label = { Text("Inicio (AAAA-MM-DDTHH:MM)") }, isError = parsedStart == null, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(end, { end = it }, label = { Text("Fin (AAAA-MM-DDTHH:MM)") }, isError = parsedEnd == null || (parsedStart != null && !parsedEnd.isAfter(parsedStart)), modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(location, { location = it }, label = { Text("Lugar") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Descripción") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                if (onDelete != null) item { TextButton(onClick = onDelete) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { Button(onClick = { onSave(event.copy(title = title.trim(), institution = institution, kind = kind, start = parsedStart!!, end = parsedEnd!!, location = location.trim(), notes = notes.trim())) }, enabled = title.isNotBlank() && parsedStart != null && parsedEnd != null && parsedEnd!!.isAfter(parsedStart)) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun newEvent(defaultInstitution: Institution): CampusEvent {
    val start = LocalDateTime.now().withSecond(0).withNano(0).plusHours(1)
    return CampusEvent("manual-${System.currentTimeMillis()}", "", defaultInstitution, EventKind.ACTIVITY, start, start.plusHours(1))
}

private fun kindLabel(kind: EventKind) = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.TAREA -> "Tarea"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}
