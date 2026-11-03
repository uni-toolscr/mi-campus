package cr.micampus.app.feature.transport

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.ScreenHeader
import cr.micampus.app.core.designsystem.SettingsAction
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import cr.micampus.app.core.model.TransportDataset
import cr.micampus.app.core.model.TransportService
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

private val shortDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", costaRica)
private val heroTime12 = DateTimeFormatter.ofPattern("h:mm", costaRica)
private val heroMeridiem = DateTimeFormatter.ofPattern("a", costaRica)

private const val DATE_OPTIONS = 3
private const val HERO_TURN_MILLIS = 20_000
private const val PAST_ALPHA = 0.45f
private const val FOLLOWING_PREVIEW = 4

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransportScreen(
    state: TransportUiState,
    onInstitution: (Institution) -> Unit,
    onDirection: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val now = rememberCurrentMinute()
    val today = now.toLocalDate()
    // The hero always leads with what's next, even when that is tomorrow's first bus.
    val next = remember(state.serviceDays, now) { nextDeparture(state.serviceDays, now) }
    val todayService = state.serviceDays.firstOrNull { it.date == today }
    // The list follows the hero's service day unless the student picked another day below it.
    val listDate = state.date ?: next?.date ?: today
    val followsHero = next != null && listDate == next.date
    val listService = if (state.date != null) state.service else state.serviceDays.firstOrNull { it.date == listDate }
    val listDepartures = remember(listService) {
        listService?.takeIf { it.status == ServiceStatus.VERIFIED }?.departureTimes().orEmpty()
    }
    val isToday = listDate == today
    val highlighted = if (next != null && next.date == listDate) next.index else -1
    val heroStatus = if (next == null) todayService ?: state.service else null
    val selectedDirection = state.directions.firstOrNull { it.id == state.selectedDirectionId }
    val route = selectedDirection?.let { "${it.from} → ${it.to}" }.orEmpty()

    val listState = rememberLazyListState()
    // The hero's day starts as a short preview; "Ver las N salidas" expands it to the whole day.
    var showAll by rememberSaveable(state.institution, state.selectedDirectionId, listDate) { mutableStateOf(false) }
    // Departed buses fold behind one row (keeping the latest for context) so upcoming ones lead the list.
    var showDeparted by rememberSaveable(state.institution, state.selectedDirectionId, listDate) { mutableStateOf(false) }
    val folded = foldedDepartureCount(listDepartures.size, highlighted, isToday, showDeparted)

    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp).testTag("transport-list"),
        state = listState,
        contentPadding = edgeToEdgeContentPadding(bottomExtra = FloatingToolbarClearance),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "header", contentType = "header") {
            ScreenHeader(
                "Transporte",
                // The institution prefix also keeps this text distinct from the direction chip label.
                subtitle = selectedDirection?.let { "${state.institution.name} · ${it.from} → ${it.to}" }
                    ?: "Horarios oficiales guardados en el dispositivo",
            ) {
                SettingsAction(onOpenSettings)
            }
        }
        section(key = "institutions", contentType = "button-group") {
            // Both institutions stay visible/selectable here even if only one is enabled in Ajustes.
            ConnectedToggleGroup(
                options = Institution.entries,
                selected = state.institution,
                label = { it.name },
                onSelect = onInstitution,
            )
        }
        section(key = "directions", contentType = "directions") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.directions, key = { it.id }) { direction ->
                    FilterChip(
                        selected = state.selectedDirectionId == direction.id,
                        onClick = { onDirection(direction.id) },
                        label = { Text("${direction.from} → ${direction.to}") },
                    )
                }
            }
        }
        section(key = "hero", contentType = "hero") {
            if (next != null) {
                NextDepartureHero(
                    overline = nextDepartureOverline(next.date, today),
                    time = next.time,
                    countdown = relativeDurationText(minutesUntil(now, next.dateTime)),
                    support = nextDepartureSupport(next, todayService, today, state.use12hClock),
                    use12h = state.use12hClock,
                )
            } else {
                ServiceStatusCard(heroStatus, state.use12hClock)
            }
        }
        if (followsHero && !showAll && next != null) {
            val following = followingDepartures(next, FOLLOWING_PREVIEW)
            val more = hasMoreDepartures(next, FOLLOWING_PREVIEW)
            val count = following.size + if (more) 1 else 0
            if (count > 0) {
                dayHeading(transportDayHeading(next.date, today))
                itemsIndexed(following, key = { index, time -> "following-$index-$time" }, contentType = { _, _ -> "departure" }) { index, time ->
                    DepartureRow(
                        time = time,
                        index = index,
                        count = count,
                        timing = if (next.date == today) DepartureTiming.LATER else DepartureTiming.SCHEDULED,
                        minutes = minutesUntil(now, LocalDateTime.of(next.date, time)),
                        route = route,
                        use12h = state.use12hClock,
                    )
                }
                if (more) {
                    item(key = "show-all", contentType = "action-row") {
                        ActionRow(
                            label = allDeparturesLabel(next.departures.size, next.date, today),
                            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            index = count - 1,
                            count = count,
                            onClick = { showAll = true },
                        )
                    }
                }
            }
        } else if (listDepartures.isNotEmpty()) {
            dayHeading(transportDayHeading(listDate, today))
            val collapsible = followsHero
            val shown = listDepartures.size - folded + (if (folded > 0) 1 else 0) + (if (collapsible) 1 else 0)
            if (folded > 0) {
                item(key = "departed-fold", contentType = "departure") {
                    SegmentedListItem(
                        onClick = { showDeparted = true },
                        shapes = ListItemDefaults.segmentedShapes(0, shown),
                        colors = groupedListColors(),
                        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                        trailingContent = { Text("Mostrar", color = MaterialTheme.colorScheme.primary) },
                    ) { Text("$folded salidas anteriores") }
                }
            }
            itemsIndexed(listDepartures.drop(folded), key = { index, time -> "departure-${index + folded}-$time" }, contentType = { _, _ -> "departure" }) { offset, time ->
                val index = offset + folded
                val timing = when {
                    index == highlighted -> DepartureTiming.NEXT
                    !isToday -> DepartureTiming.SCHEDULED
                    highlighted < 0 || index < highlighted -> DepartureTiming.DEPARTED
                    else -> DepartureTiming.LATER
                }
                DepartureRow(
                    time = time,
                    index = offset + if (folded > 0) 1 else 0,
                    count = shown,
                    timing = timing,
                    minutes = minutesUntil(now, LocalDateTime.of(listDate, time)),
                    route = route,
                    use12h = state.use12hClock,
                )
            }
            if (collapsible) {
                item(key = "show-less", contentType = "action-row") {
                    ActionRow(
                        label = "Ver menos",
                        icon = Icons.Outlined.ExpandLess,
                        index = shown - 1,
                        count = shown,
                        onClick = { showAll = false },
                    )
                }
            }
        } else if (listService != null && (next != null || listService.status != heroStatus?.status)) {
            // Browsing a day without verified departures (e.g. a Sunday) while the hero shows another day.
            section(key = "day-status", contentType = "status") { ServiceStatusCard(listService, state.use12hClock) }
        }
        if (selectedDirection != null) {
            section(key = "dates", contentType = "button-group") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ver otro día", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val options = remember(today) { (0 until DATE_OPTIONS).map { today.plusDays(it.toLong()) } }
                    ConnectedToggleGroup(
                        options = options,
                        selected = listDate,
                        label = { transportDateLabel(it, today) },
                        onSelect = onDate,
                    )
                }
            }
        }
        val dataset = state.dataset
        if (dataset != null) {
            section(key = "dataset", contentType = "dataset") { DatasetLine(dataset, today) }
        }
    }
}

private fun LazyListScope.dayHeading(text: String) = section(key = "day-heading", contentType = "section-title") {
    Text(
        text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedToggleGroup(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val checked = option == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(option)
                },
                modifier = Modifier.weight(1f).semantics {
                    role = Role.RadioButton
                    this.selected = checked
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/** Departed buses hidden behind the fold row: all but the latest one, and only for today's list. */
internal fun foldedDepartureCount(total: Int, nextIndex: Int, isToday: Boolean, showDeparted: Boolean): Int {
    if (!isToday || showDeparted) return 0
    val departed = if (nextIndex < 0) total else nextIndex
    val hidden = departed - 1
    // Folding a single row into a "1 salida anterior" row would save nothing.
    return if (hidden > 1) hidden else 0
}

/** Sections sit apart while the departure rows between them keep the tight segmented gap. */
private fun LazyListScope.section(key: String, contentType: String, content: @Composable () -> Unit) =
    item(key = key, contentType = contentType) { Box(Modifier.padding(top = 12.dp)) { content() } }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NextDepartureHero(overline: String, time: LocalTime, countdown: String, support: String?, use12h: Boolean) {
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val timeText = time.format(if (use12h) heroTime12 else timeFormatter(false))
    val meridiem = if (use12h) time.format(heroMeridiem) else null
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().testTag("transport-hero"),
    ) {
        Box {
            // Ambient shape peeking from the corner; the Surface clips it to the card.
            RotatingShape(
                shape = MaterialShapes.Cookie9Sided,
                color = onContainer.copy(alpha = 0.08f),
                rotate = animationsEnabled,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 56.dp, y = (-56).dp).size(180.dp),
            )
            Column(
                Modifier.fillMaxWidth().padding(20.dp).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(overline, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.displayLargeEmphasized,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (meridiem != null) {
                        Text(meridiem, style = MaterialTheme.typography.titleMedium, modifier = Modifier.alignByBaseline())
                    }
                    Text(
                        countdown,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    )
                }
                if (support != null) {
                    Text(support, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RotatingShape(shape: RoundedPolygon, color: Color, rotate: Boolean, modifier: Modifier) {
    val rotationModifier = if (rotate) {
        val rotation by rememberInfiniteTransition(label = "hero-shape").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            // A constant angular speed reads as calm ambient motion; spring specs can't loop linearly.
            animationSpec = infiniteRepeatable(tween(HERO_TURN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
            label = "hero-rotation",
        )
        Modifier.graphicsLayer { rotationZ = rotation }
    } else {
        Modifier
    }
    Box(modifier.then(rotationModifier).clip(shape.toShape()).background(color))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceStatusCard(service: TransportService?, use12h: Boolean) {
    data class StatusContent(val icon: ImageVector, val shape: RoundedPolygon, val title: String, val body: String?)
    val content = when (service?.status) {
        null -> StatusContent(Icons.Outlined.Route, MaterialShapes.Cookie9Sided, "Selecciona una ruta", "Elige una dirección para ver sus salidas.")
        ServiceStatus.NO_SERVICE -> StatusContent(
            Icons.Outlined.EventBusy,
            MaterialShapes.Sunny,
            "No hay servicio este día",
            service.nextValidDate?.let { "Próximo día de servicio: ${formatShortDate(it)}" },
        )
        ServiceStatus.EXPIRED -> StatusContent(
            Icons.Outlined.History,
            MaterialShapes.Clover4Leaf,
            "Horario vencido",
            "No se muestra cuenta regresiva fuera del rango verificado.",
        )
        // Only reached when the verified schedule has no later service day to lead with.
        ServiceStatus.VERIFIED -> StatusContent(
            Icons.Outlined.NightsStay,
            MaterialShapes.Cookie4Sided,
            "No hay más salidas",
            service.departureTimes().lastOrNull()?.let { "La última salida fue a las ${it.format(timeFormatter(use12h))}." },
        )
        ServiceStatus.UNKNOWN -> StatusContent(Icons.Outlined.DirectionsBus, MaterialShapes.Pill, "Horario no disponible", null)
    }
    val expired = service?.status == ServiceStatus.EXPIRED
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(20.dp).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = content.shape.toShape(),
                color = if (expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        content.icon,
                        contentDescription = null,
                        tint = if (expired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(content.title, style = MaterialTheme.typography.titleLarge)
                if (content.body != null) {
                    Text(content.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private enum class DepartureTiming { DEPARTED, NEXT, LATER, SCHEDULED }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DepartureRow(time: LocalTime, index: Int, count: Int, timing: DepartureTiming, minutes: Long, route: String, use12h: Boolean) {
    val isNext = timing == DepartureTiming.NEXT
    val contentColor = if (isNext) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = if (timing == DepartureTiming.DEPARTED) Modifier.alpha(PAST_ALPHA) else Modifier,
        colors = ListItemDefaults.segmentedColors(
            containerColor = if (isNext) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = contentColor,
        ),
        trailingContent = {
            Text(
                when (timing) {
                    DepartureTiming.DEPARTED -> "Salió"
                    DepartureTiming.NEXT -> "Próxima"
                    DepartureTiming.LATER -> relativeDurationText(minutes)
                    DepartureTiming.SCHEDULED -> route
                },
                style = if (isNext) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                color = if (isNext) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(
            time.format(timeFormatter(use12h)),
            style = if (isNext) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
            color = contentColor,
        )
    }
}

/** Last row of a departure group that expands or collapses the day's list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionRow(label: String, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = groupedListColors(),
        trailingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    ) { Text(label, color = MaterialTheme.colorScheme.primary) }
}

/** Provenance collapsed to one tappable line; the full verification details open in a dialog. */
@Composable
private fun DatasetLine(dataset: TransportDataset, today: LocalDate) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClickLabel = "Ver detalles del horario") { showDetails = true }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("transport-dataset"),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = secondary, modifier = Modifier.size(18.dp))
        Text(datasetSummary(dataset, today), style = MaterialTheme.typography.labelLarge, color = secondary)
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("Información verificada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${dataset.source} · ${dataset.lastVerified}", style = MaterialTheme.typography.bodyMedium)
                    Text("Vigencia: ${dataset.verifiedFrom} – ${dataset.verifiedUntil}", style = MaterialTheme.typography.bodyMedium, color = secondary)
                    dataset.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = secondary) }
                    if (dataset.stops.isNotEmpty()) {
                        Text("Paradas: ${dataset.stops.joinToString()}", style = MaterialTheme.typography.bodyMedium, color = secondary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Cerrar") } },
        )
    }
}

/** Wall-clock time truncated to the minute, ticking on each minute boundary while the screen is started. */
@Composable
private fun rememberCurrentMinute(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Restarting on STARTED re-reads the clock after the device slept, when delays pause.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = LocalDateTime.now()
                now = current.truncatedTo(ChronoUnit.MINUTES)
                delay(Duration.between(current, now.plusMinutes(1)).toMillis().coerceAtLeast(1))
            }
        }
    }
    return now
}

/** "en 25 min", "en 1 h 5 min", "en 7 h 53 min", "en 1 día 8 h"; "Saliendo" once the departure minute has arrived. */
internal fun relativeDurationText(minutes: Long): String {
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    return when {
        minutes <= 0 -> "Saliendo"
        // Past a day the minutes are noise; a weekend gap reads as "en 1 día 8 h".
        days > 0 -> "en $days ${if (days == 1L) "día" else "días"}" + if (hours > 0) " $hours h" else ""
        minutes < 60 -> "en $minutes min"
        minutes % 60 == 0L -> "en $hours h"
        else -> "en $hours h ${minutes % 60} min"
    }
}

internal fun minutesUntil(now: LocalDateTime, departure: LocalDateTime): Long =
    Duration.between(now, departure).toMinutes().coerceAtLeast(0)

/** "Hoy", "Mañana", otherwise the short es-CR date such as "vie 30 oct". */
internal fun transportDateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Hoy"
    today.plusDays(1) -> "Mañana"
    else -> formatShortDate(date)
}

// Android's ICU data may add abbreviation dots or a comma ("vie., 30 oct."); keep the compact form.
private fun formatShortDate(date: LocalDate): String =
    date.format(shortDateFormatter).replace(".", "").replace(",", "")
