package cr.micampus.app.feature.calendar

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.LocalDockVisible
import cr.micampus.app.core.designsystem.ScreenHeader
import cr.micampus.app.core.designsystem.SettingsAction
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.courseAccentPalette
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.formatTimeRange
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.CourseStyle
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.isExpired
import cr.micampus.app.platform.calendar.CalendarChoice
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce

/** Room for the FAB menu's toggle button stacked above the dock. */
private val CalendarFloatingActionsClearance = FloatingToolbarClearance + 80.dp

private val AgendaKindChips = listOf(
    EventKind.EXAM to "Exámenes",
    EventKind.QUIZ to "Quices",
    EventKind.TAREA to "Tareas",
    EventKind.ACTIVITY to "Actividades",
    EventKind.CLASS to "Clases",
)

@Composable
fun CalendarScreen(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    expanded: Boolean,
    onOpenSettings: () -> Unit,
    onOpenEvent: (CampusEvent) -> Unit = {},
    onOpenCourse: (String) -> Unit = {},
    onImport: () -> Unit = {},
    openClassEditor: Boolean = false,
    onClassEditorOpened: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<CampusEvent?>(null) }
    var showClassEditor by remember { mutableStateOf(false) }
    var calendars by remember { mutableStateOf<List<CalendarChoice>>(emptyList()) }
    var showCalendars by remember { mutableStateOf(false) }
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }
    var showHorarioOptions by remember { mutableStateOf(false) }
    var selectedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var showExportInfo by remember { mutableStateOf(false) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
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
        if (state.presentation != CalendarPresentation.AGENDA) selectedEventIds = emptySet()
    }
    LaunchedEffect(state.agendaEvents) {
        val visibleIds = state.agendaEvents.mapTo(mutableSetOf(), CampusEvent::id)
        selectedEventIds = selectedEventIds.intersect(visibleIds)
    }
    LaunchedEffect(openClassEditor) {
        if (openClassEditor) {
            showClassEditor = true
            onClassEditorOpened()
        }
    }
    BackHandler(selectedEventIds.isNotEmpty()) { selectedEventIds = emptySet() }
    BackHandler(fabMenuExpanded) { fabMenuExpanded = false }
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
    val now = rememberCurrentMinute()
    val courseColors = rememberCourseColors(state.events, state.courseStyles)
    val safeBottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val floatingContentClearance = CalendarFloatingActionsClearance + safeBottomInset
    val onAgendaEvent: (CampusEvent) -> Unit = { event ->
        if (selectedEventIds.isEmpty()) onOpenEvent(event)
        else selectedEventIds = selectedEventIds.toggle(event.id)
    }
    val onAgendaLongPress: (CampusEvent) -> Unit = {
        fabMenuExpanded = false
        selectedEventIds = selectedEventIds.toggle(it.id)
    }
    @Composable
    fun Agenda(modifier: Modifier) = AgendaPanel(
        events = state.agendaEvents,
        loading = state.loading,
        now = now,
        use12h = state.use12hClock,
        courseColors = courseColors,
        filters = state.filters,
        enabledInstitutions = state.enabledInstitutions,
        onQueryChange = viewModel::setCourseQuery,
        onApplyFilters = { kinds, institutions -> viewModel.applyAgendaFilters(kinds, institutions) },
        selectedIds = selectedEventIds,
        onEvent = onAgendaEvent,
        onLongPress = onAgendaLongPress,
        trailingContentClearance = floatingContentClearance,
        modifier = modifier,
    )
    @Composable
    fun Horario(modifier: Modifier) = HorarioPanel(
        state.events,
        state.use12hClock,
        state.courseStyles,
        onOpenCourse = onOpenCourse,
        onSaveStyle = viewModel::saveCourseStyle,
        showLocation = state.horarioShowLocation,
        shortDayLabels = state.horarioShortDayLabels,
        trailingContentClearance = floatingContentClearance,
        modifier = modifier,
    )
    @Composable
    fun Week(modifier: Modifier) = WeekPanel(
        weekStart = state.weekStart,
        events = state.events,
        now = now,
        use12h = state.use12hClock,
        courseColors = courseColors,
        onWeek = viewModel::setWeek,
        onEvent = onOpenEvent,
        trailingContentClearance = floatingContentClearance,
        modifier = modifier,
    )
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { scaffoldPadding ->
        Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeHorizontalInsets()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 20.dp),
            ) {
                if (selectedEventIds.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedEventIds = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Salir de selección") }
                        Text("${selectedEventIds.size} seleccionados", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { confirmBatchDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar eventos seleccionados") }
                    }
                } else {
                    val pending = state.agendaEvents.count { !it.isExpired(now) && !it.completed }
                    ScreenHeader(
                        "Calendario",
                        Modifier.padding(top = 20.dp),
                        subtitle = when (state.presentation) {
                            CalendarPresentation.HORARIO -> null
                            CalendarPresentation.WEEK -> weekRangeLabel(state.weekStart)
                            CalendarPresentation.AGENDA -> "$pending ${if (pending == 1) "pendiente" else "pendientes"}"
                        },
                    ) {
                        if (state.presentation == CalendarPresentation.HORARIO) {
                            IconButton(onClick = { showHorarioOptions = true }, modifier = Modifier.testTag("horario-options-action")) {
                                Icon(Icons.Outlined.Tune, contentDescription = "Opciones de horario")
                            }
                        }
                        CalendarOverflowMenu(onExport = { showExportInfo = true })
                        SettingsAction(onOpenSettings)
                    }
                }
                PresentationSwitcher(state.presentation, viewModel::setPresentation)
                if (expanded && state.presentation != CalendarPresentation.WEEK) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Week(Modifier.weight(0.8f).fillMaxHeight())
                        if (state.presentation == CalendarPresentation.HORARIO) Horario(Modifier.weight(1.2f)) else Agenda(Modifier.weight(1.2f))
                    }
                } else when (state.presentation) {
                    CalendarPresentation.HORARIO -> Horario(Modifier.weight(1f))
                    CalendarPresentation.WEEK -> Week(Modifier.weight(1f))
                    CalendarPresentation.AGENDA -> Agenda(Modifier.weight(1f))
                }
            }
            val motion = MaterialTheme.motionScheme
            AnimatedVisibility(
                visible = selectedEventIds.isEmpty() && (LocalDockVisible.current || fabMenuExpanded),
                enter = scaleIn(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeHorizontalInsets()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(end = 16.dp, bottom = FloatingToolbarClearance),
            ) {
                CalendarFabMenu(
                    expanded = fabMenuExpanded,
                    onExpandedChange = { fabMenuExpanded = it },
                    onImport = onImport,
                    onCreateClass = { showClassEditor = true },
                    onCreateEvent = { editor = newEvent(state.defaultInstitution) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresentationSwitcher(selected: CalendarPresentation, onSelect: (CalendarPresentation) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val modes = CalendarPresentation.entries
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = selected == mode,
                onCheckedChange = {
                    if (selected != mode) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(mode)
                    }
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when (mode) {
                        CalendarPresentation.HORARIO -> "Horario"
                        CalendarPresentation.WEEK -> "Semana"
                        CalendarPresentation.AGENDA -> "Agenda"
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CalendarFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onCreateClass: () -> Unit,
    onCreateEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier
                    .testTag("calendar-create-action")
                    .semantics { contentDescription = if (expanded) "Cerrar menú" else "Agregar" },
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                Icon(rememberVectorPainter(icon), contentDescription = null, modifier = Modifier.animateIcon({ checkedProgress }))
            }
        },
        modifier = modifier.testTag("calendar-floating-actions"),
    ) {
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); onImport() },
            text = { Text("Importar PDF") },
            icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
            modifier = Modifier.testTag("calendar-import-action"),
        )
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); onCreateClass() },
            text = { Text("Clase") },
            icon = { Icon(Icons.Outlined.School, contentDescription = null) },
        )
        FloatingActionButtonMenuItem(
            onClick = { onExpandedChange(false); onCreateEvent() },
            text = { Text("Evento") },
            icon = { Icon(Icons.Outlined.Event, contentDescription = null) },
        )
    }
}

/** Actions that aren't about adding things; kept out of the FAB so it only ever creates. */
@Composable
private fun CalendarOverflowMenu(onExport: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("calendar-overflow-action")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Más opciones")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Exportar al calendario") },
                leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                onClick = { open = false; onExport() },
                modifier = Modifier.testTag("calendar-export-action"),
            )
        }
    }
}

/** Resolves any event to its course's palette entry, matching the Horario's color assignment. */
@Stable
private class CourseColors(
    private val palette: List<Pair<Color, Color>>,
    private val accents: List<Color>,
    private val indexByKey: Map<String, Int>,
    private val titlesByCode: Map<String, String>,
) {
    fun container(event: CampusEvent): Pair<Color, Color>? = courseKeyOf(event, titlesByCode, indexByKey.keys)?.let(indexByKey::get)?.let(palette::get)
    fun accent(courseKey: String): Color? = indexByKey[courseKey]?.let(accents::get)
}

@Composable
private fun rememberCourseColors(events: List<CampusEvent>, courseStyles: List<CourseStyle>): CourseColors {
    val palette = coursePalette()
    val accents = courseAccentPalette()
    return remember(events, courseStyles, palette, accents) {
        val keys = deriveScheduleSlots(events).map(ScheduleSlot::title)
        val indexByKey = assignCourseColors(keys, courseStyles.associateBy(CourseStyle::courseKey), palette.size)
        CourseColors(palette, accents, indexByKey, classTitlesByCode(events))
    }
}

@Composable
private fun AgendaPanel(
    events: List<CampusEvent>,
    loading: Boolean,
    now: LocalDateTime,
    use12h: Boolean,
    courseColors: CourseColors,
    filters: CalendarFilters,
    enabledInstitutions: List<Institution>,
    onQueryChange: (String) -> Unit,
    onApplyFilters: (Set<EventKind>, Set<Institution>) -> Unit,
    selectedIds: Set<String>,
    onEvent: (CampusEvent) -> Unit,
    onLongPress: (CampusEvent) -> Unit,
    trailingContentClearance: Dp,
    modifier: Modifier = Modifier,
) {
    var pendingOnly by rememberSaveable { mutableStateOf(true) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    val rows = remember(events, now, pendingOnly, showPast) { agendaRows(events, now, pendingOnly, showPast) }
    Column(modifier) {
        AgendaSearchField(filters.courseQuery, onQueryChange)
        AgendaFilterChips(pendingOnly, { pendingOnly = it }, filters, enabledInstitutions, onApplyFilters)
        when {
            loading -> LoadingState()
            events.isEmpty() -> EmptyState("No hay eventos", "Ajusta los filtros, crea un evento o importa un PDF.")
            else -> AgendaList(rows, now.toLocalDate(), use12h, now, courseColors, selectedIds, onEvent, onLongPress, { showPast = !showPast }, trailingContentClearance)
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun AgendaSearchField(initialQuery: String, onQueryChange: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        snapshotFlow { query }.debounce(200).collect { latestOnQueryChange(it) }
    }
    TextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Buscar curso, tarea o nota") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { query = ""; latestOnQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Borrar búsqueda")
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().testTag("agenda-search"),
    )
}

@Composable
private fun AgendaFilterChips(
    pendingOnly: Boolean,
    onPendingOnly: (Boolean) -> Unit,
    filters: CalendarFilters,
    enabledInstitutions: List<Institution>,
    onApplyFilters: (Set<EventKind>, Set<Institution>) -> Unit,
) {
    @Composable
    fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("Pendientes", pendingOnly) { onPendingOnly(!pendingOnly) }
        AgendaKindChips.forEach { (kind, label) ->
            val shown = kind !in filters.excludedKinds
            Chip(label, shown) {
                onApplyFilters(if (shown) filters.excludedKinds + kind else filters.excludedKinds - kind, filters.excludedInstitutions)
            }
        }
        if (enabledInstitutions.size > 1) {
            enabledInstitutions.forEach { institution ->
                val shown = institution !in filters.excludedInstitutions
                Chip(institution.name, shown) {
                    onApplyFilters(filters.excludedKinds, if (shown) filters.excludedInstitutions + institution else filters.excludedInstitutions - institution)
                }
            }
        }
    }
}

@Composable
private fun AgendaList(
    rows: List<AgendaRow>,
    today: LocalDate,
    use12h: Boolean,
    now: LocalDateTime,
    courseColors: CourseColors,
    selectedIds: Set<String>,
    onEvent: (CampusEvent) -> Unit,
    onLongPress: (CampusEvent) -> Unit,
    onTogglePast: () -> Unit,
    trailingContentClearance: Dp,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("calendar-scroll-content"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(top = 4.dp, bottom = trailingContentClearance),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        rows.forEach { row ->
            when (row) {
                is AgendaRow.PastFold -> item(key = "past-fold", contentType = "fold") {
                    FoldRow(
                        label = "${row.count} ${if (row.count == 1) "evento pasado" else "eventos pasados"}",
                        expanded = row.expanded,
                        showLabel = "Mostrar",
                        onToggle = onTogglePast,
                    )
                }
                is AgendaRow.Month -> item(key = "month-${row.month}", contentType = "month") {
                    Text(
                        monthDividerLabel(row.month, today),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                is AgendaRow.DayHeader -> stickyHeader(key = "day-${row.date}", contentType = "day-header") {
                    Text(
                        agendaDayHeader(row.date, today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = 12.dp, bottom = 6.dp)
                            .semantics { heading() },
                    )
                }
                is AgendaRow.Event -> item(key = row.event.id, contentType = "event") {
                    AgendaEventItem(row, use12h, now, courseColors, row.event.id in selectedIds, onEvent, onLongPress)
                }
            }
        }
        if (rows.none { it is AgendaRow.Event }) {
            item(key = "no-pending", contentType = "empty") {
                EmptyState("Sin eventos pendientes", "No tienes nada por delante con estos filtros.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgendaEventItem(
    row: AgendaRow.Event,
    use12h: Boolean,
    now: LocalDateTime,
    courseColors: CourseColors,
    selected: Boolean,
    onEvent: (CampusEvent) -> Unit,
    onLongPress: (CampusEvent) -> Unit,
) {
    val event = row.event
    val countdown = agendaCountdown(event, now)
    val detail = eventTimeAndPlace(event, use12h)
    SegmentedListItem(
        selected = selected,
        onClick = { onEvent(event) },
        onLongClick = { onLongPress(event) },
        shapes = ListItemDefaults.segmentedShapes(row.index, row.count),
        colors = groupedListColors(),
        leadingContent = {
            if (selected) {
                KindIconContainer(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary)
            } else {
                KindIconContainer(kindIcon(event.kind), courseColors.container(event) ?: defaultEventColors())
            }
        },
        supportingContent = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = countdown?.let { badge ->
            {
                if (badge.urgent) PillBadge(badge.text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                else PillBadge(badge.text, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        modifier = Modifier
            .then(if (row.past || event.completed) Modifier.alpha(0.6f) else Modifier)
            .semantics {
                contentDescription = listOfNotNull(
                    when {
                        selected -> "Evento seleccionado: ${event.title}"
                        event.completed -> "Evento hecho: ${event.title}"
                        row.past -> "Evento vencido: ${event.title}"
                        else -> "Evento: ${event.title}"
                    },
                    "${kindLabel(event.kind)}, ${event.start.toLocalDate().let { "${weekdayName(it.dayOfWeek)} ${it.dayOfMonth}" }}",
                    detail,
                    countdown?.text,
                ).joinToString(", ")
            },
    ) {
        Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (event.completed) TextDecoration.LineThrough else null)
    }
}

@Composable
private fun WeekPanel(
    weekStart: LocalDate,
    events: List<CampusEvent>,
    now: LocalDateTime,
    use12h: Boolean,
    courseColors: CourseColors,
    onWeek: (LocalDate) -> Unit,
    onEvent: (CampusEvent) -> Unit,
    trailingContentClearance: Dp,
    modifier: Modifier = Modifier,
) {
    val today = now.toLocalDate()
    var showPast by rememberSaveable(weekStart) { mutableStateOf(false) }
    val strip = remember(weekStart, events, today) { weekStripDays(weekStart, events, today) }
    val rows = remember(weekStart, events, today, showPast) { weekListRows(weekStart, events, today, showPast) }
    val listState = key(weekStart) { rememberLazyListState() }
    var scrollTarget by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(scrollTarget, rows) {
        val target = scrollTarget ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is WeekListRow.DayHeader && it.date == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            scrollTarget = null
        }
    }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val latestWeekStart by rememberUpdatedState(weekStart)
    Column(
        modifier
            .testTag("calendar-week-content")
            .draggable(
                state = rememberDraggableState { dragTotal += it },
                orientation = Orientation.Horizontal,
                onDragStarted = { dragTotal = 0f },
                onDragStopped = {
                    when {
                        dragTotal <= -swipeThreshold -> onWeek(latestWeekStart.plusDays(7))
                        dragTotal >= swipeThreshold -> onWeek(latestWeekStart.minusDays(7))
                    }
                    dragTotal = 0f
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWeek(weekStart.minusDays(7)) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Semana anterior")
            }
            Text(weekRangeLabel(weekStart), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            if (weekStart != mondayOf(today)) {
                TextButton(onClick = { onWeek(today) }) { Text("Hoy") }
            }
            IconButton(onClick = { onWeek(weekStart.plusDays(7)) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Semana siguiente")
            }
        }
        WeekStrip(strip, courseColors) { date ->
            if (rows.none { it is WeekListRow.DayHeader && it.date == date }) showPast = true
            scrollTarget = date
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = trailingContentClearance),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            items(rows, key = ::weekRowKey, contentType = { it::class }) { row ->
                when (row) {
                    is WeekListRow.PastFold -> FoldRow(pastFoldLabel(row), row.expanded, showLabel = "Ver", onToggle = { showPast = !showPast })
                    is WeekListRow.DayHeader -> Text(
                        weekDayHeader(row.date, today),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (row.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
                    )
                    is WeekListRow.Classes -> FlowRow(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.classes.forEach { event -> ClassChip(event, use12h, now, courseColors) { onEvent(event) } }
                    }
                    is WeekListRow.Event -> WeekEventItem(row, use12h, now, courseColors, onEvent)
                    is WeekListRow.Empty -> Text(
                        "Sin actividades",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun weekRowKey(row: WeekListRow): String = when (row) {
    is WeekListRow.PastFold -> "past-fold"
    is WeekListRow.DayHeader -> "day-${row.date}"
    is WeekListRow.Classes -> "classes-${row.date}"
    is WeekListRow.Event -> row.event.id
    is WeekListRow.Empty -> "empty-${row.date}"
}

@Composable
private fun WeekStrip(days: List<WeekStripDay>, courseColors: CourseColors, onDay: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        days.forEach { day ->
            val classes = day.classKeys.size
            Column(
                Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClickLabel = "Ir al día") { onDay(day.date) }
                    .semantics {
                        contentDescription = buildString {
                            append(weekdayName(day.date.dayOfWeek).replaceFirstChar(Char::titlecase))
                            append(" ${day.date.dayOfMonth}")
                            if (day.isToday) append(", hoy")
                            if (classes > 0) append(", $classes ${if (classes == 1) "clase" else "clases"}")
                            if (day.hasDeadline) append(", con entregas o exámenes")
                        }
                    }
                    .padding(vertical = 6.dp)
                    .alpha(if (day.isPast) 0.5f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(weekdayAbbreviation(day.date.dayOfWeek), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .size(32.dp)
                        .background(if (day.isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (day.isToday) FontWeight.Bold else null,
                        color = if (day.isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(Modifier.height(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    day.classKeys.take(4).forEach { key ->
                        Box(Modifier.width(6.dp).fillMaxHeight().background(courseColors.accent(key) ?: MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                    }
                    if (day.hasDeadline) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassChip(event: CampusEvent, use12h: Boolean, now: LocalDateTime, courseColors: CourseColors, onClick: () -> Unit) {
    val (container, onContainer) = courseColors.container(event) ?: defaultEventColors()
    val time = event.start.toLocalTime().format(timeFormatter(use12h))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = onContainer,
        modifier = Modifier
            .then(if (event.isExpired(now)) Modifier.alpha(0.6f) else Modifier)
            .semantics { contentDescription = "Clase: ${event.title}, ${formatTimeRange(event.start, event.end, use12h)}" },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(event.title.trim(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WeekEventItem(row: WeekListRow.Event, use12h: Boolean, now: LocalDateTime, courseColors: CourseColors, onEvent: (CampusEvent) -> Unit) {
    val event = row.event
    val badge = weekEventBadge(event.kind)
    SegmentedListItem(
        onClick = { onEvent(event) },
        shapes = ListItemDefaults.segmentedShapes(row.index, row.count),
        colors = groupedListColors(),
        leadingContent = { KindIconContainer(kindIcon(event.kind), courseColors.container(event) ?: defaultEventColors()) },
        supportingContent = { Text(eventTimeAndPlace(event, use12h), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = badge?.let { { PillBadge(it, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) } },
        modifier = if (event.isExpired(now) || event.completed) Modifier.alpha(0.6f) else Modifier,
    ) {
        Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (event.completed) TextDecoration.LineThrough else null)
    }
}

@Composable
private fun FoldRow(label: String, expanded: Boolean, showLabel: String, onToggle: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggle) { Text(if (expanded) "Ocultar" else showLabel) }
        }
    }
}

@Composable
private fun KindIconContainer(icon: ImageVector, colors: Pair<Color, Color>) {
    Box(
        Modifier.size(36.dp).background(colors.first, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.second, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PillBadge(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun defaultEventColors(): Pair<Color, Color> =
    MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer

private fun kindIcon(kind: EventKind): ImageVector = when (kind) {
    EventKind.TAREA -> Icons.AutoMirrored.Outlined.Assignment
    EventKind.EXAM -> Icons.Outlined.Quiz
    EventKind.QUIZ -> Icons.AutoMirrored.Outlined.HelpOutline
    EventKind.ACTIVITY -> Icons.Outlined.Event
    EventKind.CLASS -> Icons.Outlined.School
    EventKind.TRANSIT -> Icons.Outlined.DirectionsBus
}

private fun eventTimeAndPlace(event: CampusEvent, use12h: Boolean): String = listOfNotNull(
    if (event.allDay) "Todo el día" else formatTimeRange(event.start, event.end, use12h),
    event.location.takeIf(String::isNotBlank),
).joinToString(" · ")

/** Current wall-clock time that refreshes about once a minute so expiry styling flips live. */
@Composable
internal fun rememberCurrentMinute(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = LocalDateTime.now()
        }
    }
    return now
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
