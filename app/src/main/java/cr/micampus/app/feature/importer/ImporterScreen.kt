package cr.micampus.app.feature.importer

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.timeFormatter
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.document.DocumentStatus
import cr.micampus.app.data.document.ImportedDocument
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImporterScreen(state: ImporterUiState, viewModel: ImporterViewModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<CalendarEventDraft?>(null) }
    var selectedDraftIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDiscardSelected by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onImporterBackgrounded() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.importPdfs(uris) }
    LaunchedEffect(state.openUri) {
        val uri = state.openUri ?: return@LaunchedEffect
        try {
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No hay una aplicación disponible para abrir este archivo.", android.widget.Toast.LENGTH_LONG).show()
        } finally {
            viewModel.consumeOpenUri()
        }
    }
    LaunchedEffect(state.stage, state.drafts) {
        selectedDraftIds = if (state.stage == ImportStage.REVIEW) {
            selectedDraftIds.intersect(state.drafts.mapTo(mutableSetOf(), CalendarEventDraft::id))
        } else emptySet()
    }
    BackHandler(selectedDraftIds.isNotEmpty()) { selectedDraftIds = emptySet() }
    if (confirmDiscardSelected) {
        AlertDialog(
            onDismissRequest = { confirmDiscardSelected = false },
            title = { Text("Descartar ${selectedDraftIds.size} borradores") },
            text = { Text("Esta acción eliminará permanentemente los borradores seleccionados.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.discardDrafts(selectedDraftIds)
                    selectedDraftIds = emptySet()
                    confirmDiscardSelected = false
                }) { Text("Descartar") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscardSelected = false }) { Text("Cancelar") } },
        )
    }
    if (state.stage == ImportStage.NEEDS_CONSENT) {
        AlertDialog(
            onDismissRequest = { viewModel.decideCloud(false) },
            title = { Text("Procesamiento opcional en la nube") },
            text = {
                Text(
                    "Solo para este lote se enviará el texto extraído —nunca los PDF originales— a Gemini. " +
                        "La app puede cambiar automáticamente entre modelos si alguno limita la solicitud.\n\n" +
                        state.consentDocumentNames.joinToString("\n") { "• $it" },
                )
            },
            confirmButton = { Button(onClick = { viewModel.decideCloud(true) }) { Text("Acepto para este lote") } },
            dismissButton = { TextButton(onClick = { viewModel.decideCloud(false) }) { Text("Continuar manualmente") } },
        )
    }
    editing?.let { draft -> DraftEditor(draft, enabledInstitutions = state.enabledInstitutions, onDismiss = { editing = null }, onSave = { viewModel.updateDraft(it); editing = null }) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (selectedDraftIds.isEmpty()) "Documentos y fuentes" else "${selectedDraftIds.size} seleccionados") },
                navigationIcon = {
                    if (selectedDraftIds.isEmpty()) {
                        IconButton(onClick = { viewModel.cancel(); onClose() }) { Icon(Icons.Outlined.Close, contentDescription = "Cerrar importación") }
                    } else {
                        IconButton(onClick = { selectedDraftIds = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Salir de selección") }
                    }
                },
                actions = {
                    if (selectedDraftIds.isNotEmpty()) {
                        IconButton(onClick = {
                            viewModel.confirmDrafts(selectedDraftIds)
                            selectedDraftIds = emptySet()
                        }) { Icon(Icons.Outlined.Check, contentDescription = "Confirmar borradores seleccionados") }
                        IconButton(onClick = { confirmDiscardSelected = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Descartar borradores seleccionados") }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (state.stage) {
            ImportStage.IDLE -> ImportStart(state, Modifier.padding(padding), onPick = { picker.launch(arrayOf("application/pdf", "text/*", "image/*")) }, onManual = { viewModel.addManualDraft() }, onRetry = viewModel::retryDocument, onOpen = viewModel::requestOpenDocument, onDelete = viewModel::deleteDocument)
            ImportStage.EXTRACTING -> BatchProgress(state, Modifier.padding(padding))
            ImportStage.NEEDS_NANO -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Gemini Nano está disponible para descargar", style = MaterialTheme.typography.headlineSmall)
                Text(state.message.orEmpty())
                Button(onClick = viewModel::downloadNano, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Download, contentDescription = null); Text(" Descargar en el dispositivo") }
                if (state.cloudAiEnabled) OutlinedButton(onClick = viewModel::useCloudInsteadOfNano, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Usar nube para este lote") }
                OutlinedButton(onClick = viewModel::useManualEntry, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continuar manualmente") }
                if (state.nanoBytes > 0 || state.nanoTotalBytes != null) {
                    val total = state.nanoTotalBytes
                    if (total != null && total > 0) {
                        LinearWavyProgressIndicator(progress = { (state.nanoBytes.coerceAtMost(total).toFloat() / total) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(nanoProgressLabel(state.nanoBytes, state.nanoTotalBytes))
                }
            }
            ImportStage.LOCAL_ERROR -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EmptyState("No se pudo usar Gemini Nano", state.message.orEmpty())
                Button(onClick = viewModel::retryNano, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text(" Reintentar en el dispositivo")
                }
                if (state.cloudAiEnabled) OutlinedButton(onClick = viewModel::useCloudInsteadOfNano, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Usar nube para este lote") }
                OutlinedButton(onClick = viewModel::useManualEntry, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continuar manualmente") }
            }
            ImportStage.NEEDS_CONSENT -> Column(Modifier.padding(padding)) { LoadingState("Esperando tu decisión") }
            ImportStage.SELECT_GROUP -> GroupPicker(state, Modifier.padding(padding), onSelect = viewModel::selectGroup, onSkip = viewModel::skipGroupSelection)
            ImportStage.REVIEW -> DraftReview(
                state,
                Modifier.padding(padding),
                selectedDraftIds = selectedDraftIds,
                onEdit = { draft ->
                    if (selectedDraftIds.isEmpty()) editing = draft
                    else selectedDraftIds = selectedDraftIds.toggle(draft.id)
                },
                onLongPress = { selectedDraftIds = selectedDraftIds.toggle(it.id) },
                onConfirm = viewModel::confirmDraft,
                onConfirmAll = viewModel::confirmAllReady,
                onDelete = viewModel::discardDraft,
                onAdd = viewModel::addManualDraft,
            )
            ImportStage.MANUAL -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EmptyState("Entrada manual", state.message ?: "Agrega un evento y completa sus datos.")
                Button(onClick = { viewModel.addManualDraft() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, contentDescription = null); Text(" Agregar evento") }
                OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) { Text("Elegir PDF") }
            }
            ImportStage.ERROR -> Column(Modifier.padding(padding)) { EmptyState("No se pudo importar", state.message.orEmpty()) }
        }
    }
}

private fun nanoProgressLabel(downloaded: Long, total: Long?): String {
    fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    return if (total != null && total > 0) {
        val percent = ((downloaded.coerceAtMost(total) * 100) / total).toInt()
        "${mb(downloaded)} de ${mb(total)} ($percent %)"
    } else {
        "${mb(downloaded)} descargados"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportStart(state: ImporterUiState, modifier: Modifier, onPick: () -> Unit, onManual: () -> Unit, onRetry: (String) -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item {
            Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text("Archivos y fuentes para la IA", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Agrega hasta 10 archivos (PDF, texto o imagen). Se guardan de forma privada en este dispositivo y el asistente puede usarlos como fuente.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Text("Primero se intenta texto local y OCR. Nada se confirma automáticamente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Button(onClick = onPick, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Agregar archivos", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = onManual, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp)) { Text("Ingresar evento manualmente") }
        }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) } }
        if (state.documents.isNotEmpty()) item { SectionTitle("Archivos guardados") }
        itemsIndexed(state.documents, key = { _, document -> document.id }) { index, document ->
            DocumentLibraryItem(document, ListItemDefaults.segmentedShapes(index, state.documents.size), onRetry, onOpen, onDelete)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BatchProgress(state: ImporterUiState, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item(contentType = "header") { Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.currentDocumentName?.let { "Procesando $it…" } ?: "Guardando PDF localmente…", style = MaterialTheme.typography.titleLarge)
            if (state.partsTotal > 0) {
                LinearWavyProgressIndicator(
                    progress = { state.partsDone / state.partsTotal.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Progreso del análisis: parte ${state.partsDone} de ${state.partsTotal}" },
                )
                Text(state.message.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Análisis en curso" },
                )
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("Los documentos se procesan uno por uno para evitar exceder límites.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        itemsIndexed(state.batchItems, key = { _, item -> item.id }) { index, item ->
            SegmentedListItem(
                colors = groupedListColors(),
                shapes = ListItemDefaults.segmentedShapes(index, state.batchItems.size),
                supportingContent = {
                    Text(batchStatusLabel(item), color = if (item.status == BatchItemStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ) { Text(item.displayName) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DocumentLibraryItem(document: ImportedDocument, shapes: ListItemShapes, onRetry: (String) -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    SegmentedListItem(
        colors = groupedListColors(),
        onClick = { onOpen(document.id) },
        shapes = shapes,
        modifier = Modifier.semantics { contentDescription = "Archivo guardado ${document.displayName}" },
        leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${formatBytes(document.byteSize)} · ${documentStatusLabel(document)}", color = if (document.status == DocumentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                document.modelsUsed.takeIf(List<String>::isNotEmpty)?.let { models ->
                    Text(models.joinToString(" · ") { diagnosticModelLabel(it) }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = { onRetry(document.id) }) { Icon(Icons.Outlined.Refresh, contentDescription = "Reintentar ${document.displayName}") }
                IconButton(onClick = { onDelete(document.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar ${document.displayName}") }
            }
        },
    ) { Text(document.displayName) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

internal fun diagnosticModelLabel(model: String): String = when {
    model == "gemini-nano" -> "Local: Gemini Nano"
    model.startsWith("gemini-nano/") -> "Local: Gemini Nano · ${model.removePrefix("gemini-nano/")}"
    else -> "Nube: $model"
}

private fun batchStatusLabel(item: BatchImportItem) = listOf(item.status.name.lowercase().replaceFirstChar { it.uppercase() }, item.message).filterNotNull().joinToString(" · ")
private fun documentStatusLabel(document: ImportedDocument) = when (document.status) {
    DocumentStatus.STORED -> "Guardado"
    DocumentStatus.PROCESSING -> "Procesando"
    DocumentStatus.COMPLETED ->
        if (document.draftCount > 0) "Procesado (${document.draftCount} borradores)" else "Disponible como fuente para el asistente"
    DocumentStatus.MANUAL -> "Requiere revisión manual"
    DocumentStatus.FAILED -> "Error: ${document.error.orEmpty()}"
    DocumentStatus.CANCELLED -> "Cancelado"
    DocumentStatus.INTERRUPTED -> "Interrumpido; puedes reintentar"
}
private fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MiB".format(bytes / (1024.0 * 1024.0)) else "${bytes / 1024} KiB"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GroupPicker(state: ImporterUiState, modifier: Modifier, onSelect: (cr.micampus.app.core.model.CourseGroup) -> Unit, onSkip: () -> Unit) {
    val syllabus = state.syllabus ?: return
    val formatter = timeFormatter(state.use12hClock)
    val dayLabels = mapOf(
        java.time.DayOfWeek.MONDAY to "lunes", java.time.DayOfWeek.TUESDAY to "martes", java.time.DayOfWeek.WEDNESDAY to "miércoles",
        java.time.DayOfWeek.THURSDAY to "jueves", java.time.DayOfWeek.FRIDAY to "viernes", java.time.DayOfWeek.SATURDAY to "sábado", java.time.DayOfWeek.SUNDAY to "domingo",
    )
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item(contentType = "header") {
            Text("¿Cuál es tu grupo?", style = MaterialTheme.typography.headlineSmall)
            state.currentDocumentName?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(
                listOfNotNull(syllabus.course?.name, syllabus.course?.code).joinToString(" · ").ifBlank { "Curso detectado" },
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Elige tu grupo para generar las clases del cronograma con el tema de cada día.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        itemsIndexed(syllabus.groups, key = { _, group -> group.label }) { index, group ->
            val days = group.days.sorted().joinToString(" y ") { dayLabels[it] ?: it.name }
            val hours = listOfNotNull(group.startTime?.format(formatter), group.endTime?.format(formatter)).joinToString("–")
            SegmentedListItem(
                colors = groupedListColors(),
                onClick = { onSelect(group) },
                shapes = ListItemDefaults.segmentedShapes(index, syllabus.groups.size),
                modifier = Modifier.semantics { contentDescription = "Grupo ${group.label}" },
                supportingContent = {
                    Column {
                        Text(listOf(days, hours).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Horario no detectado" })
                        group.instructor?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
            ) { Text("Grupo ${group.label}", style = MaterialTheme.typography.titleMedium) }
        }
        item { OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp)) { Text("Omitir: solo exámenes y entregas") } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DraftReview(
    state: ImporterUiState,
    modifier: Modifier,
    selectedDraftIds: Set<String>,
    onEdit: (CalendarEventDraft) -> Unit,
    onLongPress: (CalendarEventDraft) -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmAll: () -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val formatter = timeFormatter(state.use12hClock)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        item(contentType = "header") {
            Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Revisa antes de confirmar", style = MaterialTheme.typography.headlineSmall)
                Text("Los campos ambiguos permanecen vacíos o marcados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selectedDraftIds.isEmpty() && state.drafts.count { it.issues.isEmpty() } > 1) {
                    Button(onClick = onConfirmAll, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
                        Text("Confirmar todos los que están listos (${state.drafts.count { it.issues.isEmpty() }})")
                    }
                }
            }
        }
        itemsIndexed(state.drafts, key = { _, draft -> draft.id }) { index, draft ->
            val selected = draft.id in selectedDraftIds
            SegmentedListItem(
                colors = groupedListColors(),
                selected = selected,
                onClick = { onEdit(draft) },
                onLongClick = { onLongPress(draft) },
                shapes = ListItemDefaults.segmentedShapes(index, state.drafts.size),
                modifier = Modifier.semantics {
                    contentDescription = if (selected) "Borrador seleccionado: ${draft.title ?: "Sin título"}" else "Borrador: ${draft.title ?: "Sin título"}"
                },
                overlineContent = draft.sourceDocumentId
                    ?.let { sourceId -> state.documents.firstOrNull { it.id == sourceId } }
                    ?.let { source -> { Text("Origen: ${source.displayName}", color = MaterialTheme.colorScheme.primary) } },
                trailingContent = if (selected) {
                    { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
                supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hours = listOfNotNull(draft.startTime?.format(formatter), draft.endTime?.format(formatter)).joinToString("–")
                    Text(listOfNotNull(draft.date?.toString(), hours.takeIf(String::isNotBlank), draft.location).joinToString(" · ").ifBlank { "Faltan fecha y hora" })
                    draft.description?.takeIf(String::isNotBlank)?.let { Text(it, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (draft.issues.isNotEmpty()) Text("Revisar: ${draft.issues.joinToString { issueLabel(it) }}", color = MaterialTheme.colorScheme.error)
                    draft.evidence?.excerpt?.takeIf(String::isNotBlank)?.let { Text("Página ${draft.sourcePage ?: "?"}: $it", style = MaterialTheme.typography.bodySmall) }
                    if (selectedDraftIds.isEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onDelete(draft.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Descartar borrador") }
                            TextButton(onClick = { onEdit(draft) }) { Text("Editar") }
                            Button(onClick = { onConfirm(draft.id) }, shapes = ButtonDefaults.shapes()) { Text("Confirmar") }
                        }
                    }
                } },
            ) { Text(draft.title ?: "Sin título", style = MaterialTheme.typography.titleMedium) }
        }
        if (selectedDraftIds.isEmpty()) item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Icon(Icons.Outlined.Add, contentDescription = null); Text(" Agregar manualmente") } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) } }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun DraftEditor(draft: CalendarEventDraft, enabledInstitutions: List<Institution>, onDismiss: () -> Unit, onSave: (CalendarEventDraft) -> Unit) {
    var title by remember(draft.id) { mutableStateOf(draft.title.orEmpty()) }
    var date by remember(draft.id) { mutableStateOf(draft.date?.toString().orEmpty()) }
    var start by remember(draft.id) { mutableStateOf(draft.startTime?.toString().orEmpty()) }
    var end by remember(draft.id) { mutableStateOf(draft.endTime?.toString().orEmpty()) }
    var location by remember(draft.id) { mutableStateOf(draft.location.orEmpty()) }
    var course by remember(draft.id) { mutableStateOf(draft.course?.code.orEmpty()) }
    var description by remember(draft.id) { mutableStateOf(draft.description.orEmpty()) }
    var institution by remember(draft.id) { mutableStateOf(draft.institution) }
    var category by remember(draft.id) { mutableStateOf(draft.category ?: EventCategory.OTHER) }
    var institutionMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    // Offer only enabled institutions, plus the draft's current institution if it's disabled.
    val institutionOptions = (enabledInstitutions + listOfNotNull(draft.institution)).distinct()
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val parsedStart = runCatching { LocalTime.parse(start) }.getOrNull()
    val parsedEnd = runCatching { LocalTime.parse(end) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar borrador") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    TextButton(onClick = { institutionMenu = true }) { Text("Institución: ${institution?.name ?: "Seleccionar"}") }
                    DropdownMenu(institutionMenu, { institutionMenu = false }) {
                        institutionOptions.forEach { value -> DropdownMenuItem({ Text(value.name) }, { institution = value; institutionMenu = false }) }
                    }
                }
                item { TextButton(onClick = { categoryMenu = true }) { Text("Categoría: ${category.name}") }; DropdownMenu(categoryMenu, { categoryMenu = false }) { EventCategory.values().forEach { value -> DropdownMenuItem({ Text(value.name) }, { category = value; categoryMenu = false }) } } }
                item { OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, isError = date.isNotBlank() && parsedDate == null, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(start, { start = it }, label = { Text("Inicio (HH:MM)") }, isError = start.isNotBlank() && parsedStart == null, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(end, { end = it }, label = { Text("Fin (HH:MM)") }, isError = end.isNotBlank() && (parsedEnd == null || (parsedStart != null && !parsedEnd!!.isAfter(parsedStart))), modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(location, { location = it }, label = { Text("Lugar") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(course, { course = it }, label = { Text("Curso") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
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
                onSave(draft.copy(title = title.ifBlank { null }, institution = institution, category = category, date = parsedDate, startTime = parsedStart, endTime = parsedEnd, location = location.ifBlank { null }, course = course.ifBlank { null }?.let { Course(it, null) }, description = description.ifBlank { null }, issues = issues))
            }) { Text("Guardar borrador") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun issueLabel(issue: ImportIssue) = when (issue) {
    ImportIssue.AMBIGUOUS, ImportIssue.AMBIGUOUS_DATE -> "fecha ambigua"
    ImportIssue.INFERRED_YEAR -> "año inferido"
    ImportIssue.INFERRED_TIME -> "Hora inferida del horario"
    ImportIssue.MISSING_DATE -> "falta fecha"
    ImportIssue.MISSING_TIME -> "falta hora"
    ImportIssue.INVALID_RANGE -> "rango inválido"
    ImportIssue.PAST -> "fecha pasada"
    ImportIssue.DUPLICATE -> "posible duplicado"
}
