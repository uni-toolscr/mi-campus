package cr.micampus.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.designsystem.eventDateTimeFormatter
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.data.institution.UpcomingDeparture
@Composable
fun HomeScreen(state: HomeUiState, onImport: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
        contentPadding = edgeToEdgeContentPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Inicio", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Tu campus, disponible sin conexión", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Importar carta al estudiante (PDF)", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.loading) item { LoadingState() }
        else {
            item { SectionTitle("Próximos eventos") }
            val nextEvent = state.events.firstOrNull()
            if (nextEvent == null) {
                item { EmptyState("Todavía no hay eventos", "Importa la carta al estudiante o el programa de tu curso en PDF, o crea un evento desde Calendario.") }
            } else {
                item { HeroEventCard(nextEvent, state.use12hClock) }
                if (state.events.size > 1) items(state.events.drop(1), key = CampusEvent::id) { EventCard(it, use12h = state.use12hClock) }
            }

            item { SectionTitle("Próximos buses") }
            if (state.buses.isEmpty()) item { EmptyState("Sin salidas próximas verificadas", "Revisa Transporte para el próximo día de servicio.") }
            else items(state.buses, key = { "${it.institution}-${it.directionId}" }) { bus -> BusCard(bus, state.use12hClock) }
        }
    }
}

@Composable
private fun HeroEventCard(event: CampusEvent, use12h: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(eventKindLabel(event.kind).uppercase(), style = MaterialTheme.typography.labelLarge)
            Text(event.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${event.start.format(eventDateTimeFormatter(use12h))} – ${event.end.toLocalTime().format(timeFormatter(use12h))}", style = MaterialTheme.typography.titleMedium)
            if (event.location.isNotBlank()) Text(event.location, style = MaterialTheme.typography.bodyMedium)
            if (event.notes.isNotBlank()) Text(event.notes, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EventCard(event: CampusEvent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, use12h: Boolean = false) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${event.start.format(eventDateTimeFormatter(use12h))} – ${event.end.toLocalTime().format(timeFormatter(use12h))}")
            Text(event.institution.name + " · " + eventKindLabel(event.kind), color = MaterialTheme.colorScheme.primary)
            if (event.location.isNotBlank()) Text(event.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.notes.isNotBlank()) Text(event.notes, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) ElevatedCard(modifier.fillMaxWidth()) { content() }
    else ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { content() }
}

@Composable
private fun BusCard(bus: UpcomingDeparture, use12h: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column {
                Text("${bus.institution.name} · ${bus.departure.toLocalTime().format(timeFormatter(use12h))}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("${bus.from} → ${bus.to}", color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Horario verificado · sin conexión", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
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
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}
