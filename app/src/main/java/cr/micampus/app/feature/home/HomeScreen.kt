package cr.micampus.app.feature.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import cr.micampus.app.core.designsystem.coursePalette
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.ScreenHeader
import cr.micampus.app.core.designsystem.SettingsAction
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.courseAccentPalette
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.eventDateTimeFormatter
import cr.micampus.app.core.designsystem.formatTimeRange
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.institution.UpcomingDeparture
import cr.micampus.app.feature.calendar.formatShortDuration
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val homeDateFormatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-CR"))

/** "Miércoles 28 de octubre". */
internal fun homeDateLabel(date: LocalDate): String = date.format(homeDateFormatter).replaceFirstChar(Char::titlecase)

internal fun heroOverline(kind: EventKind, timing: HeroTiming): String = if (timing == HeroTiming.Now) {
    when (kind) {
        EventKind.CLASS -> "CLASE EN CURSO"
        EventKind.EXAM -> "EXAMEN EN CURSO"
        EventKind.QUIZ -> "QUIZ EN CURSO"
        EventKind.TAREA -> "ENTREGA DE HOY"
        EventKind.ACTIVITY -> "ACTIVIDAD EN CURSO"
        EventKind.TRANSIT -> "TRANSPORTE"
    }
} else when (kind) {
    EventKind.CLASS -> "PRÓXIMA CLASE"
    EventKind.EXAM -> "PRÓXIMO EXAMEN"
    EventKind.QUIZ -> "PRÓXIMO QUIZ"
    EventKind.TAREA -> "PRÓXIMA ENTREGA"
    EventKind.ACTIVITY -> "PRÓXIMA ACTIVIDAD"
    EventKind.TRANSIT -> "PRÓXIMO TRANSPORTE"
}

/** "Ahora", "en 25 min", "Mañana 8:00", "Jue 8:00", or a full date beyond the coming week. */
internal fun heroTimingLabel(timing: HeroTiming, today: LocalDate, use12h: Boolean): String = when (timing) {
    HeroTiming.Now -> "Ahora"
    is HeroTiming.StartsIn -> "en ${formatShortDuration(timing.minutes)}"
    is HeroTiming.Later -> {
        val time = timing.start.format(timeFormatter(use12h))
        when (ChronoUnit.DAYS.between(today, timing.start.toLocalDate())) {
            1L -> "Mañana $time"
            in 2L..6L -> "${shortDayLabel(timing.start.dayOfWeek)} $time"
            else -> timing.start.format(eventDateTimeFormatter(use12h)).replaceFirstChar(Char::titlecase)
        }
    }
}

/** "10:00 – 11:50 · Aula 204"; deadlines (start equals end) show a single time. */
internal fun heroTimeLine(event: CampusEvent, use12h: Boolean): String {
    val time = when {
        event.allDay -> "Todo el día"
        event.start == event.end -> event.start.format(timeFormatter(use12h))
        else -> formatTimeRange(event.start, event.end, use12h)
    }
    return listOfNotNull(time, event.location.takeIf(String::isNotBlank)).joinToString(" · ")
}

/** "Bus UCR → Heredia"; the institution is added when neither endpoint already names it. */
internal fun busRouteLabel(bus: UpcomingDeparture): String {
    val institution = bus.institution.name
    val route = "${bus.from} → ${bus.to}"
    return if (bus.from == institution || bus.to == institution) "Bus $route" else "Bus $institution · $route"
}

internal fun busCountdownLabel(minutes: Long): String = if (minutes <= 0) "Saliendo" else "en ${formatShortDuration(minutes)}"

/** "1 examen", "2 exámenes · 1 quiz", or null when the week has no evaluations. */
internal fun weekEvaluationsLabel(week: WeekSummary): String? = listOfNotNull(
    week.exams.takeIf { it > 0 }?.let { if (it == 1) "1 examen" else "$it exámenes" },
    week.quizzes.takeIf { it > 0 }?.let { if (it == 1) "1 quiz" else "$it quices" },
).joinToString(" · ").ifEmpty { null }

private fun longDayLabel(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "lunes"
    DayOfWeek.TUESDAY -> "martes"
    DayOfWeek.WEDNESDAY -> "miércoles"
    DayOfWeek.THURSDAY -> "jueves"
    DayOfWeek.FRIDAY -> "viernes"
    DayOfWeek.SATURDAY -> "sábado"
    DayOfWeek.SUNDAY -> "domingo"
}

private fun shortDayLabel(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lun"
    DayOfWeek.TUESDAY -> "Mar"
    DayOfWeek.WEDNESDAY -> "Mié"
    DayOfWeek.THURSDAY -> "Jue"
    DayOfWeek.FRIDAY -> "Vie"
    DayOfWeek.SATURDAY -> "Sáb"
    DayOfWeek.SUNDAY -> "Dom"
}

/** Where Inicio's cards lead. Defaults are no-ops so previews and screenshot tests stay one-liners. */
class HomeActions(
    val onOpenEvent: (CampusEvent) -> Unit = {},
    val onOpenCourse: (String) -> Unit = {},
    val onOpenBus: (UpcomingDeparture?) -> Unit = {},
    val onOpenAgenda: () -> Unit = {},
    val onSetCompleted: (CampusEvent, Boolean) -> Unit = { _, _ -> },
    val onImport: () -> Unit = {},
    val onAddClasses: () -> Unit = {},
    val onConnectAulaVirtual: () -> Unit = {},
)

/** "Quiz 2 hoy", "Parcial 2 mañana", "Quiz 2 el miércoles"; the course part of "Quiz 2 · Química" is dropped. */
internal fun heroEvaluationLabel(event: CampusEvent, today: LocalDate): String {
    val name = event.title.substringBefore(" · ").trim().ifEmpty { event.title }
    val day = when (val days = ChronoUnit.DAYS.between(today, event.start.toLocalDate())) {
        0L -> "hoy"
        1L -> "mañana"
        else -> if (days in 2L..6L) "el ${longDayLabel(event.start.dayOfWeek)}" else "el ${event.start.dayOfMonth}"
    }
    return "$name $day"
}

/** "en 18 min · luego 22:30" or "en 18 min · última de hoy". */
internal fun busSupportingLabel(bus: HomeBus, now: LocalDateTime, use12h: Boolean): String {
    val countdown = busCountdownLabel(Duration.between(now, bus.next.departure).toMinutes())
    val following = bus.following?.departure?.format(timeFormatter(use12h))?.let { "luego $it" } ?: "última de hoy"
    return "$countdown · $following"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(state: HomeUiState, onOpenSettings: () -> Unit, actions: HomeActions = HomeActions()) {
    val accents = courseAccentPalette()
    val palette = coursePalette()
    val colorIndex = remember(state.courseKeys, state.courseStyles, accents.size) {
        assignCourseColors(state.courseKeys, state.courseStyles, accents.size)
    }
    val neutralAccent = MaterialTheme.colorScheme.outline
    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
        contentPadding = edgeToEdgeContentPadding(bottomExtra = FloatingToolbarClearance),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "header", contentType = "header") {
            ScreenHeader("Hoy", subtitle = homeDateLabel(state.now.toLocalDate()), actions = { SettingsAction(onOpenSettings) })
        }
        if (state.loading) {
            item(key = "loading", contentType = "loading") { LoadingState() }
            return@LazyColumn
        }
        if (state.gettingStarted) {
            gettingStarted(state, actions)
            return@LazyColumn
        }
        val hero = state.hero
        item(key = "hero", contentType = "hero") {
            if (hero == null) {
                EmptyState("Nada próximo", "No tienes clases ni eventos pendientes.")
            } else {
                val colors = hero.courseKey?.let(colorIndex::get)?.let(palette::get)
                HeroCard(hero, colors, state.now.toLocalDate(), state.use12hClock, Modifier.padding(top = 16.dp)) {
                    if (hero.event.kind == EventKind.CLASS) hero.courseKey?.let(actions.onOpenCourse) else actions.onOpenEvent(hero.event)
                }
            }
        }
        item(key = "bento", contentType = "bento") {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BusTile(state.bus, state.now, state.use12hClock, Modifier.weight(1f).fillMaxHeight()) { actions.onOpenBus(state.bus?.next) }
                WeekTile(state.week, Modifier.weight(1f).fillMaxHeight(), actions.onOpenAgenda)
            }
        }
        if (hero == null && state.events.isEmpty()) return@LazyColumn
        item(key = "events-title", contentType = "section") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Próximos eventos", Modifier.weight(1f))
                TextButton(onClick = actions.onOpenAgenda) { Text("Ver todo") }
            }
        }
        if (state.events.isEmpty()) {
            item(key = "events-empty", contentType = "events-empty") {
                Text(
                    if (hero?.event?.kind == EventKind.CLASS) "Sin entregas ni exámenes próximos." else "No hay más eventos próximos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(state.events, key = { _, item -> item.event.id }, contentType = { _, _ -> "event" }) { index, item ->
                val accent = item.courseKey?.let(colorIndex::get)?.let(accents::get) ?: neutralAccent
                UpcomingEventItem(item, index, state.events.size, accent, state.use12hClock, actions)
            }
        }
    }
}

/** First run: what to do to get value out of the app, with the bus (which works without data) below. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.gettingStarted(state: HomeUiState, actions: HomeActions) {
    item(key = "start-hero", contentType = "hero") {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("EMPECEMOS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text("Arma tu semestre", style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.semantics { heading() })
                Text("Agrega tus clases y entregas y Mi Campus te avisa a tiempo.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val steps = buildList {
        add(Triple(Icons.Outlined.UploadFile, "Importar un PDF" to "Carta al estudiante o programa del curso", actions.onImport))
        add(Triple(Icons.Outlined.School, "Agregar tus clases" to "Nombre, días y horas", actions.onAddClasses))
        if (state.suggestAulaVirtual) add(Triple(Icons.Outlined.Link, "Conectar Aula Virtual UNA" to "Tareas y recursos de tus cursos", actions.onConnectAulaVirtual))
    }
    itemsIndexed(steps, key = { _, step -> "start-${step.second.first}" }, contentType = { _, _ -> "start-step" }) { index, (icon, text, onClick) ->
        SegmentedListItem(
            onClick = onClick,
            colors = groupedListColors(),
            shapes = ListItemDefaults.segmentedShapes(index, steps.size),
            modifier = Modifier.padding(top = if (index == 0) 10.dp else 0.dp),
            leadingContent = {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (index == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp),
                ) { Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) } }
            },
            supportingContent = { Text(text.second) },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        ) { Text(text.first, fontWeight = FontWeight.Medium) }
    }
    item(key = "start-meanwhile", contentType = "section") { SectionTitle("Mientras tanto", Modifier.padding(top = 12.dp)) }
    item(key = "start-bus", contentType = "bento") {
        BusTile(state.bus, state.now, state.use12hClock, Modifier.fillMaxWidth()) { actions.onOpenBus(state.bus?.next) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(hero: HomeHero, courseColors: Pair<Color, Color>?, today: LocalDate, use12h: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val container = courseColors?.first ?: MaterialTheme.colorScheme.primary
    val content = courseColors?.second ?: MaterialTheme.colorScheme.onPrimary
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val decoration = content.copy(alpha = 0.12f)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth().testTag("home-hero"),
    ) {
        Column(
            Modifier
                .drawBehind {
                    val side = 180.dp.toPx()
                    translate(size.width - side * 0.62f, size.height - side * 0.62f) {
                        drawOutline(cookie.createOutline(Size(side, side), layoutDirection, this), decoration)
                    }
                }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                listOfNotNull(heroOverline(hero.event.kind, hero.timing), hero.courseCode).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                hero.event.title,
                style = MaterialTheme.typography.headlineMediumEmphasized,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(heroTimeLine(hero.event, use12h), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = content, contentColor = container) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(heroTimingLabel(hero.timing, today, use12h), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                hero.evaluation?.let { Text(heroEvaluationLabel(it, today), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun BusTile(bus: HomeBus?, now: LocalDateTime, use12h: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val container = MaterialTheme.colorScheme.tertiaryContainer
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    if (bus == null) {
        BentoTile(container, content, Icons.Outlined.DirectionsBus, "Bus", modifier.testTag("home-bus"), onClick) {
            Text("Sin salidas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Ver Transporte", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val time = bus.next.departure.format(timeFormatter(use12h))
    BentoTile(container, content, Icons.Outlined.DirectionsBus, busRouteLabel(bus.next), modifier.testTag("home-bus"), onClick) {
        // In 12h mode the meridiem is shrunk so "10:00 a. m." still fits a half-width tile.
        Text(
            buildAnnotatedString {
                val split = time.indexOfFirst(Char::isWhitespace).takeIf { it >= 0 } ?: time.length
                append(time.substring(0, split))
                withStyle(SpanStyle(fontSize = 0.5.em)) { append(time.substring(split)) }
            },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(busSupportingLabel(bus, now, use12h), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeekTile(week: WeekSummary, modifier: Modifier, onClick: () -> Unit) {
    BentoTile(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
        Icons.Outlined.TaskAlt,
        "Esta semana",
        modifier.testTag("home-week"),
        onClick,
    ) {
        Text("${week.pending}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1)
        if (week.pending == 0) {
            Text("Nada pendiente", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(if (week.pending == 1) "pendiente" else "pendientes", style = MaterialTheme.typography.bodyMedium)
            weekEvaluationsLabel(week)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BentoTile(
    container: Color,
    content: Color,
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    body: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp).alpha(0.6f))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            body()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpcomingEventItem(item: HomeEventItem, index: Int, count: Int, accent: Color, use12h: Boolean, actions: HomeActions) {
    val event = item.event
    val supporting = listOfNotNull(
        event.start.format(eventDateTimeFormatter(use12h)),
        event.location.takeIf(String::isNotBlank),
    ).joinToString(" · ")
    SegmentedListItem(
        onClick = { actions.onOpenEvent(event) },
        colors = groupedListColors(),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Box(Modifier.width(6.dp).height(40.dp).background(accent, CircleShape)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!event.completed) item.badge?.let { EventBadge(it) }
                if (event.kind == EventKind.TAREA) {
                    Checkbox(
                        checked = event.completed,
                        onCheckedChange = { actions.onSetCompleted(event, it) },
                        modifier = Modifier.semantics { contentDescription = "Marcar ${event.title} como hecha" },
                    )
                }
            }
        },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    ) {
        Text(
            event.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (event.completed) TextDecoration.LineThrough else null,
            color = if (event.completed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
    }
}

@Composable
private fun EventBadge(badge: HomeEventBadge) {
    val urgent = badge == HomeEventBadge.TODAY || badge == HomeEventBadge.TOMORROW
    val label = when (badge) {
        HomeEventBadge.TODAY -> "Hoy"
        HomeEventBadge.TOMORROW -> "Mañana"
        HomeEventBadge.EXAM -> "Examen"
        HomeEventBadge.QUIZ -> "Quiz"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventCard(
    event: CampusEvent,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    use12h: Boolean = false,
    expired: Boolean = false,
) {
    val strike = if (expired) TextDecoration.LineThrough else null
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(16.dp).then(if (expired) Modifier.alpha(0.6f) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textDecoration = strike)
                Text("${event.start.format(eventDateTimeFormatter(use12h))} – ${event.end.toLocalTime().format(timeFormatter(use12h))}", textDecoration = strike)
                Text(event.institution.name + " · " + eventKindLabel(event.kind), color = MaterialTheme.colorScheme.primary)
                if (event.location.isNotBlank()) Text(event.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.notes.isNotBlank()) Text(event.notes, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    val colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    )
    val shape = MaterialTheme.shapes.large
    if (onClick == null && onLongClick == null) Card(modifier.fillMaxWidth(), shape = shape, colors = colors) { content() }
    else Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            .semantics {
                this.selected = selected
                contentDescription = when {
                    selected -> "Evento seleccionado: ${event.title}"
                    expired -> "Evento vencido: ${event.title}"
                    else -> "Evento: ${event.title}"
                }
            },
        shape = shape,
        colors = colors,
    ) { content() }
}

private fun eventKindLabel(kind: EventKind) = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.TAREA -> "Tarea"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 8.dp).semantics { heading() },
    )
}
