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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.Institution
import cr.micampus.app.core.model.ServiceStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag("es-CR"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportScreen(
    state: TransportUiState,
    onInstitution: (Institution) -> Unit,
    onDirection: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
        contentPadding = edgeToEdgeContentPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Transporte", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Horarios oficiales guardados en el dispositivo", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            // Both institutions stay visible/selectable here even if only one is enabled in Ajustes.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Institution.values().forEachIndexed { index, institution ->
                    SegmentedButton(
                        selected = state.institution == institution,
                        onClick = { onInstitution(institution) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = Institution.values().size),
                        label = { Text(institution.name) },
                    )
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(today, today.plusDays(1), today.plusDays(2))
                options.forEachIndexed { index, date ->
                    SegmentedButton(
                        selected = state.date == date,
                        onClick = { onDate(date) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        label = { Text(date.format(dateFormatter)) },
                    )
                }
            }
        }
        item { ServiceHero(state) }
        val service = state.service
        if (service?.status == ServiceStatus.VERIFIED) {
            item { Text("Salidas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            item { DepartureGroup(service.departures, nextDeparture = state.nextDeparture?.toLocalTime()?.toString()) }
        }
        item {
            val dataset = state.dataset
            if (dataset != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Información verificada", style = MaterialTheme.typography.titleMedium)
                    Text("${dataset.source} · ${dataset.lastVerified}", style = MaterialTheme.typography.bodyMedium)
                    Text("Vigencia: ${dataset.verifiedFrom} – ${dataset.verifiedUntil}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    dataset.notes.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("Paradas: ${dataset.stops.joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ServiceHero(state: TransportUiState) {
    val service = state.service
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (service?.status == ServiceStatus.VERIFIED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text(state.nextDeparture.toLocalTime().toString(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null)
                    Text("En ${state.minutesUntil} min · próxima salida", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun DepartureGroup(departures: List<String>, nextDeparture: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        departures.forEachIndexed { index, departure ->
            val isNext = departure == nextDeparture
            val topRadius = if (index == 0) 20.dp else 4.dp
            val bottomRadius = if (index == departures.lastIndex) 20.dp else 4.dp
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius, bottomStart = bottomRadius, bottomEnd = bottomRadius),
                colors = CardDefaults.cardColors(
                    containerColor = if (isNext) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(departure, style = MaterialTheme.typography.titleMedium)
                    if (isNext) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("Próxima", color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    } else {
                        Text("Programada", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
