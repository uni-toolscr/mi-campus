package cr.micampus.app.feature.transport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-CR"))

@Composable
fun TransportScreen(
    state: TransportUiState,
    onInstitution: (Institution) -> Unit,
    onDirection: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Transporte", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Horarios oficiales guardados en el dispositivo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Institution.values().forEach { institution ->
                    FilterChip(selected = state.institution == institution, onClick = { onInstitution(institution) }, label = { Text(institution.name) })
                }
            }
        }
        item {
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
        item {
            val today = LocalDate.now()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(today, today.plusDays(1), today.plusDays(2)).forEach { date ->
                    FilterChip(selected = state.date == date, onClick = { onDate(date) }, label = { Text(date.format(dateFormatter)) })
                }
            }
        }
        item { ServiceSummary(state) }
        val service = state.service
        if (service?.status == ServiceStatus.VERIFIED) {
            item { Text("Salidas", style = MaterialTheme.typography.titleLarge) }
            items(service.departures) { departure ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(departure, style = MaterialTheme.typography.titleMedium)
                    Text(if (state.nextDeparture?.toLocalTime()?.toString() == departure) "Próxima" else "Programada", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        item {
            val dataset = state.dataset
            if (dataset != null) {
                Text("Información verificada", style = MaterialTheme.typography.titleMedium)
                Text("${dataset.source} · ${dataset.lastVerified}", style = MaterialTheme.typography.bodyMedium)
                Text("Vigencia: ${dataset.verifiedFrom} – ${dataset.verifiedUntil}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                dataset.notes.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Paradas: ${dataset.stops.joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServiceSummary(state: TransportUiState) {
    val service = state.service
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    when (service?.status) {
                        ServiceStatus.VERIFIED -> "Servicio verificado"
                        ServiceStatus.NO_SERVICE -> "No hay servicio este día"
                        ServiceStatus.EXPIRED -> "Horario vencido"
                        else -> "Horario no disponible"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (service?.status == ServiceStatus.VERIFIED && state.nextDeparture != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null)
                    Text("Próxima: ${state.nextDeparture.toLocalTime()} · ${state.minutesUntil} min", fontWeight = FontWeight.SemiBold)
                }
            } else if (service?.status == ServiceStatus.NO_SERVICE && service.nextValidDate != null) {
                Text("Próximo día de servicio: ${service.nextValidDate.format(dateFormatter)}")
            } else if (service?.status == ServiceStatus.EXPIRED) {
                Text("No se muestra cuenta regresiva fuera del rango verificado.", color = MaterialTheme.colorScheme.error)
            } else if (service == null) {
                EmptyState("Selecciona una ruta", "Elige una dirección para ver sus salidas.")
            }
        }
    }
}
