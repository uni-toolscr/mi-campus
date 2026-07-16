package cr.micampus.app.feature.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImporterScreen(state: ImporterUiState, viewModel: ImporterViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<CalendarEventDraft?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importPdf) }
    if (state.stage == ImportStage.NEEDS_CONSENT) {
        AlertDialog(
            onDismissRequest = { viewModel.decideCloud(false) },
            title = { Text("Procesamiento opcional en la nube") },
            text = { Text("Solo para esta importación se enviará el texto extraído a Gemini usando tu clave. El PDF original nunca se envía. Puedes cancelar y continuar manualmente.") },
            confirmButton = { Button(onClick = { viewModel.decideCloud(true) }) { Text("Acepto para este PDF") } },
            dismissButton = { TextButton(onClick = { viewModel.decideCloud(false) }) { Text("Continuar manualmente") } },
        )
    }
    editing?.let { draft -> DraftEditor(draft, onDismiss = { editing = null }, onSave = { viewModel.updateDraft(it); editing = null }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importar PDF") },
                navigationIcon = { IconButton(onClick = { viewModel.cancel(); onClose() }) { Icon(Icons.Outlined.Close, contentDescription = "Cerrar importación") } },
            )
        },
    ) { padding ->
        when (state.stage) {
            ImportStage.IDLE -> ImportStart(Modifier.padding(padding), onPick = { picker.launch(arrayOf("application/pdf")) }, onManual = { viewModel.addManualDraft() })
            ImportStage.EXTRACTING -> Column(Modifier.padding(padding)) { LoadingState("Extrayendo texto y buscando eventos…") }
            ImportStage.NEEDS_NANO -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gemini Nano está disponible para descargar", style = MaterialTheme.typography.headlineSmall)
                Text(state.message.orEmpty())
                Button(onClick = viewModel::downloadNano) { Icon(Icons.Outlined.Download, contentDescription = null); Text(" Descargar en el dispositivo") }
                OutlinedButton(onClick = viewModel::useManualEntry) { Text("Continuar manualmente") }
                if (state.nanoBytes > 0) Text("${state.nanoBytes} bytes descargados")
            }
            ImportStage.NEEDS_CONSENT -> Column(Modifier.padding(padding)) { LoadingState("Esperando tu decisión") }
            ImportStage.REVIEW -> DraftReview(state, Modifier.padding(padding), onEdit = { editing = it }, onConfirm = viewModel::confirmDraft, onDelete = viewModel::discardDraft, onAdd = viewModel::addManualDraft)
            ImportStage.MANUAL -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EmptyState("Entrada manual", state.message ?: "Agrega un evento y completa sus datos.")
                Button(onClick = { viewModel.addManualDraft() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, contentDescription = null); Text(" Agregar evento") }
                OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("Elegir otro PDF") }
            }
            ImportStage.ERROR -> Column(Modifier.padding(padding)) { EmptyState("No se pudo importar", state.message.orEmpty()) }
        }
    }
}

@Composable
private fun ImportStart(modifier: Modifier, onPick: () -> Unit, onManual: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Convierte un programa de curso en borradores", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Primero se intenta texto local y OCR. Nada se confirma automáticamente.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Seleccionar PDF") }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Ingresar evento manualmente") }
    }
}

@Composable
private fun DraftReview(
    state: ImporterUiState,
    modifier: Modifier,
    onEdit: (CalendarEventDraft) -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Revisa antes de confirmar", style = MaterialTheme.typography.headlineSmall); Text("Los campos ambiguos permanecen vacíos o marcados.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.drafts, key = { it.id }) { draft ->
            ElevatedCard(onClick = { onEdit(draft) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(draft.title ?: "Sin título", style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(draft.date?.toString(), draft.startTime?.toString(), draft.location).joinToString(" · ").ifBlank { "Faltan fecha y hora" })
                    if (draft.issues.isNotEmpty()) Text("Revisar: ${draft.issues.joinToString { issueLabel(it) }}", color = MaterialTheme.colorScheme.error)
                    draft.evidence?.excerpt?.takeIf(String::isNotBlank)?.let { Text("Página ${draft.sourcePage ?: "?"}: $it", style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onDelete(draft.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Descartar borrador") }
                        TextButton(onClick = { onEdit(draft) }) { Text("Editar") }
                        Button(onClick = { onConfirm(draft.id) }) { Text("Confirmar") }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, contentDescription = null); Text(" Agregar manualmente") } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun DraftEditor(draft: CalendarEventDraft, onDismiss: () -> Unit, onSave: (CalendarEventDraft) -> Unit) {
    var title by remember(draft.id) { mutableStateOf(draft.title.orEmpty()) }
    var date by remember(draft.id) { mutableStateOf(draft.date?.toString().orEmpty()) }
    var start by remember(draft.id) { mutableStateOf(draft.startTime?.toString().orEmpty()) }
    var end by remember(draft.id) { mutableStateOf(draft.endTime?.toString().orEmpty()) }
    var location by remember(draft.id) { mutableStateOf(draft.location.orEmpty()) }
    var course by remember(draft.id) { mutableStateOf(draft.course?.code.orEmpty()) }
    var institution by remember(draft.id) { mutableStateOf(draft.institution) }
    var category by remember(draft.id) { mutableStateOf(draft.category ?: EventCategory.OTHER) }
    var institutionMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val parsedStart = runCatching { LocalTime.parse(start) }.getOrNull()
    val parsedEnd = runCatching { LocalTime.parse(end) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar borrador") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth()) }
                item { TextButton(onClick = { institutionMenu = true }) { Text("Institución: ${institution?.name ?: "Seleccionar"}") }; DropdownMenu(institutionMenu, { institutionMenu = false }) { Institution.values().forEach { value -> DropdownMenuItem({ Text(value.name) }, { institution = value; institutionMenu = false }) } } }
                item { TextButton(onClick = { categoryMenu = true }) { Text("Categoría: ${category.name}") }; DropdownMenu(categoryMenu, { categoryMenu = false }) { EventCategory.values().forEach { value -> DropdownMenuItem({ Text(value.name) }, { category = value; categoryMenu = false }) } } }
                item { OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, isError = date.isNotBlank() && parsedDate == null, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(start, { start = it }, label = { Text("Inicio (HH:MM)") }, isError = start.isNotBlank() && parsedStart == null, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(end, { end = it }, label = { Text("Fin (HH:MM)") }, isError = end.isNotBlank() && (parsedEnd == null || (parsedStart != null && !parsedEnd!!.isAfter(parsedStart))), modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(location, { location = it }, label = { Text("Lugar") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(course, { course = it }, label = { Text("Curso") }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val issues = draft.issues.toMutableSet().apply {
                    remove(ImportIssue.MISSING_DATE); remove(ImportIssue.MISSING_TIME); remove(ImportIssue.INVALID_RANGE)
                    if (parsedDate == null) add(ImportIssue.MISSING_DATE)
                    if (parsedStart == null) add(ImportIssue.MISSING_TIME)
                    if (parsedStart != null && parsedEnd != null && !parsedEnd!!.isAfter(parsedStart)) add(ImportIssue.INVALID_RANGE)
                }
                onSave(draft.copy(title = title.ifBlank { null }, institution = institution, category = category, date = parsedDate, startTime = parsedStart, endTime = parsedEnd, location = location.ifBlank { null }, course = course.ifBlank { null }?.let { Course(it, null) }, issues = issues))
            }) { Text("Guardar borrador") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun issueLabel(issue: ImportIssue) = when (issue) {
    ImportIssue.AMBIGUOUS, ImportIssue.AMBIGUOUS_DATE -> "fecha ambigua"
    ImportIssue.INFERRED_YEAR -> "año inferido"
    ImportIssue.MISSING_DATE -> "falta fecha"
    ImportIssue.MISSING_TIME -> "falta hora"
    ImportIssue.INVALID_RANGE -> "rango inválido"
    ImportIssue.PAST -> "fecha pasada"
    ImportIssue.DUPLICATE -> "posible duplicado"
}
