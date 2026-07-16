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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
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
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import java.time.format.DateTimeFormatter
import java.util.Locale

private val eventFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.forLanguageTag("es-CR"))

@Composable
fun HomeScreen(state: HomeUiState, onImport: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Inicio", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Tu campus, disponible sin conexión", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Importar programa o horario PDF")
            }
        }
        if (state.loading) item { LoadingState() }
        else {
            item { SectionTitle("Próximos eventos") }
            if (state.events.isEmpty()) item { EmptyState("Todavía no hay eventos", "Importa un PDF o crea un evento desde Calendario.") }
            else items(state.events, key = CampusEvent::id) { EventCard(it) }

            item { SectionTitle("Próximos buses") }
            if (state.buses.isEmpty()) item { EmptyState("Sin salidas próximas verificadas", "Revisa Transporte para el próximo día de servicio.") }
            else items(state.buses, key = { "${it.institution}-${it.direction}" }) { bus ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("${bus.institution.name} · ${bus.departure.toLocalTime()}", style = MaterialTheme.typography.titleMedium)
                            Text(bus.direction, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Horario verificado · sin conexión", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventCard(event: CampusEvent, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${event.start.format(eventFormatter)} – ${event.end.toLocalTime()}")
            Text(event.institution.name + " · " + eventKindLabel(event.kind), color = MaterialTheme.colorScheme.primary)
            if (event.location.isNotBlank()) Text(event.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) ElevatedCard(modifier.fillMaxWidth()) { content() }
    else ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { content() }
}

private fun eventKindLabel(kind: EventKind) = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}
