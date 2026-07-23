package cr.micampus.app.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.feature.calendar.EventEditorDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatUiState, viewModel: ChatViewModel, onBack: () -> Unit, onOpenFiles: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    var editingEvent by remember { mutableStateOf<CampusEvent?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val send = {
        if (question.isNotBlank() && state.status == ChatEngineStatus.IDLE) {
            viewModel.send(question)
            question = ""
        }
    }
    // Opens a citation: uploaded files open in a viewer; web citations (unused today) open the browser.
    val openSource: (ChatCitation) -> Unit = { citation ->
        val documentId = citation.documentId
        when {
            documentId != null -> scope.launch {
                val uri = viewModel.sourceContentUri(documentId) ?: return@launch
                try {
                    val mime = context.contentResolver.getType(uri) ?: "*/*"
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (_: ActivityNotFoundException) {
                    // No installed app can open this file type; nothing else to do.
                }
            }
            citation.url != null -> uriHandler.openUri(citation.url)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat")
                        Text(
                            "Asistente local con fuentes institucionales",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Outlined.Add, contentDescription = "Fuentes y archivos")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (state.consentPrompt) ConsentCard(viewModel)
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Escribe tu pregunta…") },
                            enabled = state.status == ChatEngineStatus.IDLE,
                            minLines = 1,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = send,
                            enabled = question.isNotBlank() && state.status == ChatEngineStatus.IDLE,
                            modifier = Modifier.size(56.dp).semantics { contentDescription = "Enviar" },
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            KnowledgeScopeNotice(state)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item("welcome") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("¿En qué te ayudo?", style = MaterialTheme.typography.titleMedium)
                                Text("Pregunta por información universitaria o escribe “tengo examen mañana a las 5” para preparar un evento.")
                            }
                        }
                    }
                }
                itemsIndexed(state.messages, key = { index, _ -> index }) { _, message ->
                    MessageBubble(message, onOpenSource = openSource, modifier = Modifier.animateItem())
                }
                state.pendingEvent?.let { proposal ->
                    item("event-${proposal.id}") {
                        EventProposalCard(
                            proposal = proposal,
                            selectedInstitutions = state.selectedInstitutions,
                            use12h = state.use12hClock,
                            onSelectInstitution = viewModel::selectEventInstitution,
                            onEdit = { editingEvent = proposal.toCampusEvent() },
                            onSave = viewModel::confirmPendingEvent,
                            onCancel = viewModel::cancelPendingEvent,
                        )
                    }
                }
                if (state.status == ChatEngineStatus.READING || state.status == ChatEngineStatus.GENERATING) {
                    item("pending") { PendingBubble(state.status.label.orEmpty(), Modifier.animateItem()) }
                }
            }
        }
    }

    LaunchedEffect(state.messages.size, state.pendingEvent, state.status) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    editingEvent?.let { event ->
        EventEditorDialog(
            event = event,
            enabledInstitutions = state.selectedInstitutions,
            use12h = state.use12hClock,
            onDismiss = { editingEvent = null },
            onSave = {
                viewModel.updatePendingEvent(it)
                editingEvent = null
            },
            onDelete = null,
        )
    }
}

@Composable
private fun KnowledgeScopeNotice(state: ChatUiState) {
    val selected = state.selectedInstitutions.joinToString { it.llmShortName }.ifBlank { "ninguna" }
    val available = state.selectedInstitutions.filter { it in state.bundledKnowledgeInstitutions }
    val unavailable = state.selectedInstitutions.filterNot { it in state.bundledKnowledgeInstitutions }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Contexto del estudiante: $selected", style = MaterialTheme.typography.labelLarge)
            if (available.isNotEmpty()) Text("Conocimiento incluido: ${available.joinToString { it.llmShortName }}", style = MaterialTheme.typography.bodySmall)
            if (unavailable.isNotEmpty()) {
                Text(
                    "Para ${unavailable.joinToString { it.llmShortName }}, importa documentos para obtener respuestas institucionales específicas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.hasDocuments) Text("Tus archivos importados también pueden usarse como fuente local.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val userBubbleShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 8.dp, bottomStart = 24.dp)
private val assistantBubbleShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp)

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onOpenSource: (ChatCitation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = message.role == ChatMessageRole.USER
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        if (user) Spacer(Modifier.weight(0.2f))
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = if (user) userBubbleShape else assistantBubbleShape,
            modifier = Modifier.weight(0.8f, fill = false).semantics {
                contentDescription = if (user) "Tú: ${message.text}" else "Asistente: ${message.text}"
            },
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (user) {
                    Text(message.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    // Render Markdown/bare-URL link embeds as clickable blue links.
                    Text(
                        buildAssistantText(message.text, MaterialTheme.colorScheme.primary),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (message.generatedInCloud) {
                    Text("Respuesta generada en la nube", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Only user-uploaded files are listed as sources; base knowledge is never cited here.
                message.citations.take(3).forEach { citation ->
                    AssistChip(
                        onClick = { onOpenSource(citation) },
                        label = { Text("${citation.institution.llmShortName} · ${citation.label}") },
                        modifier = Modifier.semantics { contentDescription = "Abrir archivo fuente ${citation.label}" },
                    )
                }
            }
        }
        if (!user) Spacer(Modifier.weight(0.2f))
    }
}

@Composable
private fun EventProposalCard(
    proposal: ChatEventProposal,
    selectedInstitutions: List<Institution>,
    use12h: Boolean,
    onSelectInstitution: (Institution) -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val event = proposal.toCampusEvent()
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Propuesta de evento",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onTertiaryContainer,
            )
            Text(proposal.title ?: "Título pendiente", color = colors.onTertiaryContainer)
            proposal.date?.let { date ->
                Text(
                    buildString {
                        append(date.format(eventDateFormatter))
                        proposal.startTime?.let { append(" · ").append(formatTime(it, use12h)) }
                        proposal.endTime?.let { append("–").append(formatTime(it, use12h)) }
                    },
                    color = colors.onTertiaryContainer,
                )
            }
            Text("Categoría: ${kindLabel(proposal.kind)}", color = colors.onTertiaryContainer)
            if (proposal.institution == null && selectedInstitutions.size > 1) {
                Text("Selecciona la institución", fontWeight = FontWeight.Bold, color = colors.onTertiaryContainer)
                selectedInstitutions.forEach { institution ->
                    AssistChip(
                        onClick = { onSelectInstitution(institution) },
                        label = { Text(institution.llmShortName) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = colors.secondaryContainer,
                            labelColor = colors.onSecondaryContainer,
                            disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
                            disabledLabelColor = colors.onSurface.copy(alpha = 0.38f),
                        ),
                    )
                }
            } else proposal.institution?.let { Text("Institución: ${it.llmShortName}", color = colors.onTertiaryContainer) }
            if (proposal.inferredEnd) Text("La hora de fin se estimó una hora después; puedes editarla.", color = colors.onTertiaryContainer)
            if (proposal.duplicate) Text("Ya existe un evento con el mismo título y hora.", color = colors.error)
            if (proposal.missingFields.isNotEmpty()) Text("Falta: ${proposal.missingFields.joinToString()}", color = colors.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEdit,
                    enabled = event != null,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = colors.primary,
                        disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
                    ),
                ) { Text("Editar") }
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
                ) { Text("Cancelar") }
            }
            Button(
                onClick = onSave,
                enabled = event != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
                    disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
                ),
            ) {
                Text(if (proposal.duplicate) "Guardar de todos modos" else "Guardar")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PendingBubble(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = assistantBubbleShape) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun ConsentCard(viewModel: ChatViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("¿Usar Gemini en la nube?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Se enviarán tu pregunta, las siglas de tus instituciones, la conversación reciente y solo los fragmentos relevantes; nunca el PDF original.",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::grantCloudConsent) { Text("Usar la nube") }
                TextButton(onClick = viewModel::denyCloudConsent) { Text("Cancelar") }
            }
        }
    }
}

private val eventDateFormatter = DateTimeFormatter.ofPattern("EEE d 'de' MMM", Locale.forLanguageTag("es-CR"))
private val time24 = DateTimeFormatter.ofPattern("HH:mm")
private val time12 = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-CR"))

private fun formatTime(time: java.time.LocalTime, use12h: Boolean): String = time.format(if (use12h) time12 else time24)

private fun kindLabel(kind: EventKind): String = when (kind) {
    EventKind.CLASS -> "Clase"
    EventKind.EXAM -> "Examen"
    EventKind.QUIZ -> "Quiz"
    EventKind.TAREA -> "Tarea"
    EventKind.ACTIVITY -> "Actividad"
    EventKind.TRANSIT -> "Transporte"
}
