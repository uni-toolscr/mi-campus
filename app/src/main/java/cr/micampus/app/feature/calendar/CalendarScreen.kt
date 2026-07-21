package cr.micampus.app.feature.calendar

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.home.EventCard
import cr.micampus.app.platform.calendar.CalendarChoice
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weekRangeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-CR"))
private val daySectionFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-CR"))
private val CalendarFloatingActionsClearance = FloatingToolbarClearance + 80.dp

@Composable
fun CalendarScreen(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    expanded: Boolean,
    onOpenSettings: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<CampusEvent?>(null) }
    var showClassEditor by remember { mutableStateOf(false) }
    var calendars by remember { mutableStateOf<List<CalendarChoice>>(emptyList()) }
    var showCalendars by remember { mutableStateOf(false) }
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }
    var showAgendaSearch by remember { mutableStateOf(false) }
    var showAgendaFilters by remember { mutableStateOf(false) }
    var showHorarioOptions by remember { mutableStateOf(false) }
    var selectedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var showExportInfo by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            calendars = viewModel.writableCalendars()
            showCalendars = true
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(state.presentation) {
        if (state.presentation != CalendarPresentation.AGENDA) {
            selectedEventIds = emptySet()
            showAgendaSearch = false
            showAgendaFilters = false
        }
    }
    LaunchedEffect(state.agendaEvents) {
        val visibleIds = state.agendaEvents.mapTo(mutableSetOf(), CampusEvent::id)
        selectedEventIds = selectedEventIds.intersect(visibleIds)
    }
    BackHandler(selectedEventIds.isNotEmpty()) { selectedEventIds = emptySet() }
    if (showAgendaFilters) {
        AgendaFilterSheet(
            state = state,
            onDismiss = { showAgendaFilters = false },
            onApply = { kinds, institutions ->
                viewModel.applyAgendaFilters(kinds, institutions)
                showAgendaFilters = false
            },
        )
    }
    if (showHorarioOptions) {
        HorarioOptionsSheet(
            showLocation = state.horarioShowLocation,
            shortDayLabels = state.horarioShortDayLabels,
            onSetShowLocation = { viewModel.setHorarioDisplay(it, state.horarioShortDayLabels) },
            onSetShortDayLabels = { viewModel.setHorarioDisplay(state.horarioShowLocation, it) },
            onDismiss = { showHorarioOptions = false },
        )
    }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("Eliminar ${selectedEventIds.size} eventos") },
            text = { Text("Se eliminarán de MiCampus y se cancelarán sus recordatorios. Los eventos ya exportados a otro calendario no se eliminarán allí.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteSelected(selectedEventIds)
                    selectedEventIds = emptySet()
                    confirmBatchDelete = false
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text("Cancelar") } },
        )
    }
    if (showExportInfo) {
        AlertDialog(
            onDismissRequest = { showExportInfo = false },
            icon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            title = { Text("Exportar al calendario") },
            text = { Text("Esta acción exportará tu horario y tu agenda al calendario predeterminado de tu dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    showExportInfo = false
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { showExportInfo = false }) { Text("Cancelar") } },
        )
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
            use12h = state.use12hClock,
            onDismiss = { editor = null },
            onSave = { viewModel.save(it); editor = null },
            onDelete = if (state.events.any { it.id == event.id }) ({ viewModel.delete(event); editor = null }) else null,
        )
    }
    if (showClassEditor) {
        ClassEditorDialog(
            enabledInstitutions = state.enabledInstitutions,
            defaultInstitution = state.defaultInstitution,
            semesterStart = state.semesterStart,
            semesterEnd = state.semesterEnd,
            use12h = state.use12hClock,
            onDismiss = { showClassEditor = false },
            onSave = { viewModel.saveClassSeries(it); showClassEditor = false },
        )
    }
    val safeBottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val floatingContentClearance = CalendarFloatingActionsClearance + safeBottomInset
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (selectedEventIds.isEmpty()) {
                val horario = state.presentation == CalendarPresentation.HORARIO
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeHorizontalInsets()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 20.dp)
                        .padding(bottom = FloatingToolbarClearance)
                        .testTag("calendar-floating-actions"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedIconButton(
                        onClick = { showExportInfo = true },
                        modifier = Modifier.size(48.dp).testTag("calendar-export-action"),
                    ) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = "Exportar eventos confirmados")
                    }
                    ExtendedFloatingActionButton(
                        onClick = { if (horario) showClassEditor = true else editor = newEvent(state.defaultInstitution) },
                        modifier = Modifier.testTag("calendar-create-action"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (horario) "Crear clase" else "Crear evento")
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .safeHorizontalInsets()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedEventIds.isNotEmpty()) {
                    IconButton(onClick = { selectedEventIds = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Salir de selección") }
                    Text("${selectedEventIds.size} seleccionados", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirmBatchDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar eventos seleccionados") }
                } else {
                    Column(Modifier.weight(1f)) { Text("Calendario", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text("Eventos confirmados", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.presentation == CalendarPresentation.AGENDA) {
                        val excludedCount = state.filters.excludedKinds.size + state.enabledInstitutions.count { it in state.filters.excludedInstitutions }
                        BadgedBox(badge = { if (excludedCount > 0) Badge { Text(excludedCount.toString()) } }) {
                            IconButton(onClick = { showAgendaFilters = true }) { Icon(Icons.Outlined.FilterList, contentDescription = "Filtrar agenda") }
                        }
                        IconButton(onClick = { showAgendaSearch = !showAgendaSearch }) { Icon(Icons.Outlined.Search, contentDescription = "Buscar en agenda") }
                    }
                    if (state.presentation == CalendarPresentation.HORARIO) {
                        IconButton(onClick = { showHorarioOptions = true }, modifier = Modifier.testTag("horario-options-action")) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Opciones de horario")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ajustes")
                    }
                }
            }
            CalendarControls(state, viewModel, showAgendaSearch) {
                viewModel.setCourseQuery("")
                showAgendaSearch = false
            }
            if (expanded && state.presentation != CalendarPresentation.WEEK) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    WeekPanel(state.weekStart, state.events, state.use12hClock, viewModel::setWeek, onEvent = { editor = it }, trailingContentClearance = floatingContentClearance, modifier = Modifier.weight(0.8f).fillMaxHeight())
                    when (state.presentation) {
                        CalendarPresentation.HORARIO -> HorarioPanel(state.events, state.use12hClock, state.courseStyles, onSlotClick = { editor = it }, onSaveStyle = viewModel::saveCourseStyle, onDeleteSeries = { viewModel.deleteClassSeries(it.title, it.day, it.start, it.end) }, showLocation = state.horarioShowLocation, shortDayLabels = state.horarioShortDayLabels, trailingContentClearance = floatingContentClearance, modifier = Modifier.weight(1.2f))
                        CalendarPresentation.AGENDA -> Agenda(
                            state.agendaEvents,
                            state.loading,
                            state.use12hClock,
                            selectedEventIds,
                            onEvent = { event ->
                                if (selectedEventIds.isEmpty()) editor = event
                                else selectedEventIds = selectedEventIds.toggle(event.id)
                            },
                            onLongPress = { selectedEventIds = selectedEventIds.toggle(it.id) },
                            trailingContentClearance = floatingContentClearance,
                            modifier = Modifier.weight(1.2f),
                        )
                        CalendarPresentation.WEEK -> Unit // handled by the full-width branch below
                    }
                }
            } else when (state.presentation) {
                CalendarPresentation.HORARIO -> HorarioPanel(state.events, state.use12hClock, state.courseStyles, onSlotClick = { editor = it }, onSaveStyle = viewModel::saveCourseStyle, onDeleteSeries = { viewModel.deleteClassSeries(it.title, it.day, it.start, it.end) }, showLocation = state.horarioShowLocation, shortDayLabels = state.horarioShortDayLabels, trailingContentClearance = floatingContentClearance, modifier = Modifier.weight(1f))
                CalendarPresentation.WEEK -> WeekPanel(state.weekStart, state.events, state.use12hClock, viewModel::setWeek, onEvent = { editor = it }, trailingContentClearance = floatingContentClearance, modifier = Modifier.weight(1f))
                CalendarPresentation.AGENDA -> Agenda(
                    state.agendaEvents,
                    state.loading,
                    state.use12hClock,
                    selectedEventIds,
                    onEvent = { event ->
                        if (selectedEventIds.isEmpty()) editor = event
                        else selectedEventIds = selectedEventIds.toggle(event.id)
                    },
                    onLongPress = { selectedEventIds = selectedEventIds.toggle(it.id) },
                    trailingContentClearance = floatingContentClearance,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarControls(state: CalendarUiState, viewModel: CalendarViewModel, showAgendaSearch: Boolean, onCloseSearch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CalendarPresentation.values().forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.presentation == mode,
                    onClick = { viewModel.setPresentation(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CalendarPresentation.values().size),
                    label = { Text(when (mode) {
                        CalendarPresentation.HORARIO -> "Horario"
                        CalendarPresentation.WEEK -> "Semana"
                        CalendarPresentation.AGENDA -> "Agenda"
                    }) },
                )
            }
        }
        if (state.presentation == CalendarPresentation.AGENDA && showAgendaSearch) {
            OutlinedTextField(
                value = state.filters.courseQuery,
                onValueChange = viewModel::setCourseQuery,
                label = { Text("Buscar por curso o texto") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = onCloseSearch) { Icon(Icons.Outlined.Close, contentDescription = "Cerrar búsqueda") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaFilterSheet(
    state: CalendarUiState,
    onDismiss: () -> Unit,
    onApply: (Set<EventKind>, Set<Institution>) -> Unit,
) {
    var excludedKinds by remember { mutableStateOf(state.filters.excludedKinds) }
    var excludedInstitutions by remember { mutableStateOf(state.filters.excludedInstitutions) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filtrar agenda", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Tipos de evento", style = MaterialTheme.typography.titleMedium)
            EventKind.values().forEach { kind ->
                val checked = kind !in excludedKinds
                FilterCheckboxRow(kindLabel(kind), checked) {
                    excludedKinds = if (checked) excludedKinds + kind else excludedKinds - kind
                }
            }
            Text("Instituciones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            state.enabledInstitutions.forEach { institution ->
                val checked = institution !in excludedInstitutions
                FilterCheckboxRow(institution.name, checked) {
                    excludedInstitutions = if (checked) excludedInstitutions + institution else excludedInstitutions - institution
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    excludedKinds = setOf(EventKind.CLASS)
                    excludedInstitutions = emptySet()
                }) { Text("Restablecer") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Button(onClick = { onApply(excludedKinds, excludedInstitutions) }) { Text("Aplicar") }
            }
        }
    }
}

@Composable
private fun FilterCheckboxRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun Agenda(
    events: List<CampusEvent>,
    loading: Boolean,
    use12hClock: Boolean,
    selectedIds: Set<String> = emptySet(),
    onEvent: (CampusEvent) -> Unit,
    onLongPress: ((CampusEvent) -> Unit)? = null,
    trailingContentClearance: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    when {
        loading -> LoadingState()
        events.isEmpty() -> EmptyState("No hay eventos", "Ajusta los filtros, crea un evento o importa un PDF.")
        else -> LazyColumn(
            modifier.testTag("calendar-scroll-content"),
            contentPadding = PaddingValues(bottom = trailingContentClearance),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(events, key = CampusEvent::id) { event ->
                EventCard(
                    event,
                    onClick = { onEvent(event) },
                    onLongClick = onLongPress?.let { action -> { action(event) } },
                    selected = event.id in selectedIds,
                    use12h = use12hClock,
                )
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HorarioOptionsSheet(
    showLocation: Boolean,
    shortDayLabels: Boolean,
    onSetShowLocation: (Boolean) -> Unit,
    onSetShortDayLabels: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Opciones de horario", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OptionSwitchRow("Mostrar ubicación", "Ver el aula o lugar en cada bloque de clase.", showLocation, onSetShowLocation)
            OptionSwitchRow("Etiquetas de día cortas", "Usar “Lun.”, “Mar.”… en vez de los nombres completos.", shortDayLabels, onSetShortDayLabels)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Listo") }
            }
        }
    }
}

@Composable
private fun OptionSwitchRow(title: String, description: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

private sealed interface WeekRow {
    data class Header(val date: LocalDate) : WeekRow
    data class Event(val event: CampusEvent) : WeekRow
}

@Composable
private fun WeekPanel(
    weekStart: LocalDate,
    events: List<CampusEvent>,
    use12h: Boolean,
    onWeek: (LocalDate) -> Unit,
    onEvent: (CampusEvent) -> Unit,
    trailingContentClearance: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val days = remember(weekStart) { weekDays(weekStart) }
    val weekEnd = weekStart.plusDays(7)
    // Classes live in the Horario view; the Semana list is for everything else (exams, tareas, etc.).
    val byDay = remember(events, weekStart) {
        events.asSequence()
            .filter { it.kind != EventKind.CLASS }
            .filter { val d = it.start.toLocalDate(); !d.isBefore(weekStart) && d.isBefore(weekEnd) }
            .sortedBy { it.start }
            .groupBy { it.start.toLocalDate() }
    }
    // Flatten to header/event rows and remember where each day's header lands so the strip can scroll to it.
    val rows = remember(byDay, weekStart) {
        buildList {
            days.forEach { day ->
                val dayEvents = byDay[day].orEmpty()
                if (dayEvents.isNotEmpty()) {
                    add(WeekRow.Header(day))
                    dayEvents.forEach { add(WeekRow.Event(it)) }
                }
            }
        }
    }
    val dayIndex = remember(rows) {
        rows.withIndex().filter { it.value is WeekRow.Header }.associate { (index, row) -> (row as WeekRow.Header).date to index }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.testTag("calendar-week-content").padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWeek(weekStart.minusDays(7)) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Semana anterior")
            }
            Text(
                "${weekStart.format(weekRangeFormatter)} – ${weekStart.plusDays(6).format(weekRangeFormatter)}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onWeek(weekStart.plusDays(7)) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Semana siguiente")
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            days.forEach { date ->
                val isToday = date == today
                val hasEvents = date in dayIndex
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = hasEvents) { dayIndex[date]?.let { target -> scope.launch { listState.animateScrollToItem(target) } } }
                        .padding(vertical = 4.dp)
                        .semantics { contentDescription = "${dayStripName(date.dayOfWeek)} ${date.dayOfMonth}${if (hasEvents) ", con eventos" else ""}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(dayStripName(date.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isToday) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(date.dayOfMonth.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (hasEvents) MaterialTheme.colorScheme.primary else Color.Transparent,
                        modifier = Modifier.size(6.dp),
                    ) {}
                }
            }
        }
        if (rows.isEmpty()) {
            EmptyState("Sin eventos esta semana", "Cambia de semana o crea un evento para verlo aquí.")
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = listState,
                contentPadding = PaddingValues(bottom = trailingContentClearance),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { row -> when (row) {
                    is WeekRow.Header -> "header-${row.date}"
                    is WeekRow.Event -> row.event.id
                } }) { row ->
                    when (row) {
                        is WeekRow.Header -> Text(
                            row.date.format(daySectionFormatter).replaceFirstChar { it.titlecase() },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        is WeekRow.Event -> EventCard(row.event, onClick = { onEvent(row.event) }, use12h = use12h)
                    }
                }
            }
        }
    }
}

private fun dayStripName(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lun."
    DayOfWeek.TUESDAY -> "Mar."
    DayOfWeek.WEDNESDAY -> "Mié."
    DayOfWeek.THURSDAY -> "Jue."
    DayOfWeek.FRIDAY -> "Vie."
    DayOfWeek.SATURDAY -> "Sáb."
    DayOfWeek.SUNDAY -> "Dom."
}
