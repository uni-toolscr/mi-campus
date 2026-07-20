package cr.micampus.app.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.model.Institution
import java.time.DayOfWeek
import java.time.LocalDate

/** Dialog for creating a weekly class: independent day/time blocks expanded over the semester range. */
@Composable
fun ClassEditorDialog(
    enabledInstitutions: List<Institution>,
    defaultInstitution: Institution,
    semesterStart: LocalDate?,
    semesterEnd: LocalDate?,
    use12h: Boolean,
    onDismiss: () -> Unit,
    onSave: (ClassScheduleSpec) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf(defaultInstitution) }
    var institutionMenu by remember { mutableStateOf(false) }
    val blocks = remember { mutableListOf(ClassBlock()).toMutableStateList() }
    var start by remember { mutableStateOf(semesterStart ?: LocalDate.now()) }
    var end by remember { mutableStateOf(semesterEnd ?: LocalDate.now().plusWeeks(16)) }
    val spec = ClassScheduleSpec(title, institution, location, notes, start, end, blocks.toList())
    val sessionCount = if (spec.isValid) ClassScheduleGenerator.generate(spec).size else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear clase") },
        text = {
            LazyColumn(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Nombre del curso") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Descripción") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(location, { location = it }, label = { Text("Lugar") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                if (enabledInstitutions.size > 1) {
                    item {
                        TextButton(onClick = { institutionMenu = true }) { Text("Institución: ${institution.name}") }
                        DropdownMenu(institutionMenu, { institutionMenu = false }) {
                            enabledInstitutions.forEach { value -> DropdownMenuItem({ Text(value.name) }, { institution = value; institutionMenu = false }) }
                        }
                    }
                }
                item { Text("Bloques semanales", style = MaterialTheme.typography.titleSmall) }
                blocks.forEachIndexed { index, block ->
                    item(key = "block-$index") {
                        BlockRow(
                            block = block,
                            use12h = use12h,
                            showRemove = blocks.size > 1,
                            onChange = { blocks[index] = it },
                            onRemove = { blocks.removeAt(index) },
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = { blocks.add(blocks.last().copy(day = blocks.last().day.plus(2))) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Agregar bloque")
                    }
                }
                item { Text("Semestre", style = MaterialTheme.typography.titleSmall) }
                item { DateField(label = "Inicio del semestre", value = start, onChange = { start = it }) }
                item { DateField(label = "Fin del semestre", value = end, onChange = { end = it }) }
                if (end.isBefore(start)) {
                    item { Text("El fin del semestre debe ser posterior al inicio", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (blocks.toSet().size != blocks.size) {
                    item { Text("Hay bloques duplicados: cada bloque debe tener un día u horario distinto", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Text(
                        if (spec.isValid) "Se crearán $sessionCount sesiones" else "Completa el nombre, los bloques y las fechas",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(spec) }, enabled = spec.isValid && sessionCount > 0) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun BlockRow(block: ClassBlock, use12h: Boolean, showRemove: Boolean, onChange: (ClassBlock) -> Unit, onRemove: () -> Unit) {
    var dayMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { dayMenu = true }) { Text(dayName(block.day)) }
            DropdownMenu(dayMenu, { dayMenu = false }) {
                DayOfWeek.values().forEach { day ->
                    DropdownMenuItem({ Text(dayName(day)) }, { onChange(block.copy(day = day)); dayMenu = false })
                }
            }
            if (showRemove) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Quitar bloque de ${dayName(block.day)}") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TimeField(label = "Inicio", value = block.start, use12h = use12h, onChange = { onChange(block.copy(start = it)) }, modifier = Modifier.weight(1f))
            TimeField(label = "Fin", value = block.end, use12h = use12h, isError = !block.isValid, onChange = { onChange(block.copy(end = it)) }, modifier = Modifier.weight(1f))
        }
    }
}

private fun dayName(day: DayOfWeek) = when (day) {
    DayOfWeek.MONDAY -> "Lunes"
    DayOfWeek.TUESDAY -> "Martes"
    DayOfWeek.WEDNESDAY -> "Miércoles"
    DayOfWeek.THURSDAY -> "Jueves"
    DayOfWeek.FRIDAY -> "Viernes"
    DayOfWeek.SATURDAY -> "Sábado"
    DayOfWeek.SUNDAY -> "Domingo"
}
