package cr.micampus.app.feature.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.*
import cr.micampus.app.data.ai.*
import cr.micampus.app.data.document.*
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import cr.micampus.app.data.diagnostics.NoOpImportDiagnostics
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.data.local.toEntity
import cr.micampus.app.platform.reminders.ReminderScheduling
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.UUID

enum class ImportStage { IDLE, EXTRACTING, NEEDS_NANO, LOCAL_ERROR, NEEDS_CONSENT, SELECT_GROUP, REVIEW, MANUAL, ERROR }
enum class BatchItemStatus { QUEUED, EXTRACTING, WAITING, COMPLETED, MANUAL, FAILED, CANCELLED }

data class BatchImportItem(
    val id: String,
    val documentId: String?,
    val displayName: String,
    val status: BatchItemStatus,
    val message: String? = null,
)

data class ImporterUiState(
    val stage: ImportStage = ImportStage.IDLE,
    val drafts: List<CalendarEventDraft> = emptyList(),
    val message: String? = null,
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    val nanoCapability: NanoCapability? = null,
    val nanoBytes: Long = 0,
    val nanoTotalBytes: Long? = null,
    val nanoFailure: NanoFailureKind? = null,
    val localAiEnabled: Boolean = false,
    val cloudAiEnabled: Boolean = false,
    val importId: String? = null,
    val enabledInstitutions: List<Institution> = Institution.values().toList(),
    val syllabus: ExtractedSyllabus? = null,
    val use12hClock: Boolean = false,
    val batchItems: List<BatchImportItem> = emptyList(),
    val currentDocumentName: String? = null,
    val consentDocumentNames: List<String> = emptyList(),
    val documents: List<ImportedDocument> = emptyList(),
    val openUri: Uri? = null,
)

internal fun ImporterUiState.beginCloudDecision(granted: Boolean): ImporterUiState? {
    if (stage != ImportStage.NEEDS_CONSENT) return null
    return copy(
        stage = ImportStage.EXTRACTING,
        message = if (granted) "Procesando con Gemini…" else "Continuando sin nube…",
    )
}

internal fun draftToCampusEvent(draft: CalendarEventDraft): CampusEvent? {
    val title = draft.title?.takeIf(String::isNotBlank)
    val date = draft.date
    val start = draft.startTime
    val end = draft.endTime
    val institution = draft.institution
    if (title == null || date == null || start == null || end == null || institution == null || !end.isAfter(start)) return null
    return CampusEvent(
        id = draft.id, title = title, institution = institution,
        kind = when (draft.category) {
            EventCategory.CLASS -> EventKind.CLASS
            EventCategory.EXAM -> EventKind.EXAM
            EventCategory.QUIZ -> EventKind.QUIZ
            EventCategory.TAREA -> EventKind.TAREA
            EventCategory.TRANSIT -> EventKind.TRANSIT
            else -> EventKind.ACTIVITY
        },
        start = LocalDateTime.of(date, start), end = LocalDateTime.of(date, end),
        location = draft.location.orEmpty(), notes = draft.description.orEmpty(), source = "importado",
        courseCode = draft.course?.code, sourceDocumentId = draft.sourceDocumentId,
    )
}

internal fun selectedDraftEvents(drafts: List<CalendarEventDraft>, ids: Set<String>): List<CampusEvent> =
    drafts.filter { it.id in ids }.mapNotNull(::draftToCampusEvent)

/** Chat transcription persistence is optional and must not interrupt document import. */
internal suspend fun saveTranscriptionAfterExtraction(
    transcriptions: DocumentTranscriptionSaver?,
    documentId: String,
    pages: List<PageText>,
) {
    runCatching { transcriptions?.save(documentId, pages) }
}

/** Applies review edits without erasing metadata owned by an existing confirmed event. */
internal fun mergeConfirmedEvent(existing: CampusEvent, edited: CampusEvent): CampusEvent = existing.copy(
    title = edited.title,
    institution = edited.institution,
    kind = edited.kind,
    start = edited.start,
    end = edited.end,
    location = edited.location,
    notes = edited.notes,
    courseCode = edited.courseCode ?: existing.courseCode,
    sourceDocumentId = edited.sourceDocumentId ?: existing.sourceDocumentId,
)

private class PerBatchConsent : CloudConsent {
    private val decisions = mutableMapOf<String, ConsentDecision>()
    override fun decisionForImport(importId: String) = decisions[importId] ?: ConsentDecision.PENDING
    fun set(importId: String, decision: ConsentDecision) { decisions[importId] = decision }
    fun clear(importId: String) { decisions.remove(importId) }
}

private data class QueuedDocument(val itemId: String, val document: ImportedDocument)
private data class CurrentWork(
    val queued: QueuedDocument,
    val attemptId: String,
    val diagnosticTraceId: String,
    var chunks: List<String> = emptyList(),
    var documentDrafts: List<CalendarEventDraft> = emptyList(),
    var modelsUsed: Set<String> = emptySet(),
    var skipLocal: Boolean = false,
    var diagnosticBackend: String? = null,
)

class ImporterViewModel(
    private val documents: DocumentImporter,
    private val library: ImportedDocumentRepository,
    private val events: EventRepository,
    private val settings: SettingsStore,
    private val reminders: ReminderScheduling,
    private val cloud: GeminiCloudEngine,
    private val nano: MlKitNanoEngine = MlKitNanoEngine(),
    private val chunker: TokenChunker = TokenChunker(),
    private val diagnostics: ImportDiagnosticsRecorder = NoOpImportDiagnostics,
    private val onDataChanged: suspend () -> Unit = {},
    private val transcriptions: DocumentTranscriptionSaver? = null,
) : ViewModel() {
    private val consent = PerBatchConsent()
    private val mutableState = MutableStateFlow(ImporterUiState())
    val state: StateFlow<ImporterUiState> = mutableState.asStateFlow()
    private val queue = ArrayDeque<QueuedDocument>()
    private var current: CurrentWork? = null
    private var batchJob: Job? = null
    private var batchId: String? = null
    private var cloudSession: CloudEventEngine? = null
    private var skipNano = false
    private var localInferenceActive = false

    init {
        viewModelScope.launch {
            library.markInterrupted()
            library.documents.collect { stored -> mutableState.update { it.copy(documents = stored) } }
        }
        viewModelScope.launch {
            events.draftModels.collect { restored ->
                if (restored.isNotEmpty() && mutableState.value.stage == ImportStage.IDLE) {
                    mutableState.update { it.copy(stage = ImportStage.REVIEW, drafts = restored, message = "Borradores restaurados") }
                }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { appSettings ->
                val enabled = appSettings.selectedInstitutions().ifEmpty { listOf(Institution.UCR) }
                mutableState.update {
                    it.copy(
                        enabledInstitutions = enabled,
                        use12hClock = appSettings.use12hClock,
                        localAiEnabled = appSettings.localAiEnabled,
                        cloudAiEnabled = appSettings.cloudAiEnabled,
                    )
                }
            }
        }
    }

    fun importPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        beginBatch()
        batchJob = viewModelScope.launch {
            val result = library.storeBatch(uris)
            val storedItems = result.stored.map { stored ->
                val itemId = UUID.randomUUID().toString()
                queue += QueuedDocument(itemId, stored.document)
                BatchImportItem(itemId, stored.document.id, stored.document.displayName, BatchItemStatus.QUEUED, if (stored.reused) "Copia local reutilizada" else "Guardado localmente")
            }
            val rejectedItems = result.rejected.map { rejected ->
                BatchImportItem(UUID.randomUUID().toString(), null, rejected.displayName, BatchItemStatus.FAILED, rejected.reason)
            }
            mutableState.update {
                it.copy(
                    batchItems = storedItems + rejectedItems,
                    consentDocumentNames = storedItems.map(BatchImportItem::displayName),
                    message = result.rejected.takeIf { rejected -> rejected.isNotEmpty() }?.joinToString("\n") { rejected -> "${rejected.displayName}: ${rejected.reason}" },
                )
            }
            processQueue()
        }
    }

    @Deprecated("Use importPdfs")
    fun importPdf(uri: Uri) = importPdfs(listOf(uri))

    fun retryDocument(documentId: String) {
        beginBatch()
        batchJob = viewModelScope.launch {
            val document = library.document(documentId)
            if (document == null) {
                mutableState.update { it.copy(stage = ImportStage.IDLE, message = "El PDF ya no está disponible.") }
                return@launch
            }
            val item = BatchImportItem(UUID.randomUUID().toString(), document.id, document.displayName, BatchItemStatus.QUEUED, "Reintento")
            queue += QueuedDocument(item.id, document)
            mutableState.update { it.copy(batchItems = listOf(item), consentDocumentNames = listOf(document.displayName)) }
            processQueue()
        }
    }

    private fun beginBatch() {
        batchJob?.cancel()
        queue.clear()
        current = null
        batchId?.let(consent::clear)
        batchId = UUID.randomUUID().toString()
        cloudSession = cloud.newSession { current?.diagnosticTraceId }
        skipNano = false
        mutableState.update {
            it.copy(
                stage = ImportStage.EXTRACTING,
                message = null,
                importId = batchId,
                syllabus = null,
                batchItems = emptyList(),
                currentDocumentName = null,
                consentDocumentNames = emptyList(),
                nanoBytes = 0,
                nanoTotalBytes = null,
                nanoFailure = null,
                partsDone = 0,
                partsTotal = 0,
            )
        }
    }

    private suspend fun processQueue() {
        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            val id = batchId ?: return
            val attemptId = library.startAttempt(queued.document.id, id)
            val aiSettings = settings.current()
            val diagnosticTraceId = diagnostics.beginAttempt(aiSettings.localAiEnabled, aiSettings.cloudAiEnabled)
            val work = CurrentWork(queued, attemptId, diagnosticTraceId)
            current = work
            nano.setDiagnosticTrace(diagnosticTraceId)
            updateItem(queued.itemId, BatchItemStatus.EXTRACTING, "Extrayendo texto y OCR")
            mutableState.update { it.copy(stage = ImportStage.EXTRACTING, currentDocumentName = queued.document.displayName, partsDone = 0, partsTotal = 0) }
            val file = library.file(queued.document.id)
            if (file == null) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "document_extraction", outcome = "failure", failure = "missing_private_copy"))
                finishCurrent(DocumentStatus.FAILED, BatchItemStatus.FAILED, "No se encontró la copia local.")
                continue
            }
            val extracted = documents.extract(file)
            val document = extracted.getOrElse { error ->
                val kind = (error as? PdfExtractionException)?.kind?.name ?: "MALFORMED"
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "document_extraction", outcome = "failure", failure = kind.lowercase()))
                finishCurrent(DocumentStatus.FAILED, BatchItemStatus.FAILED, "No se pudo leer el PDF ($kind).")
                continue
            }
            work.chunks = chunker.chunk(document.pages)
            saveTranscriptionAfterExtraction(transcriptions, queued.document.id, document.pages)
            diagnostics.record(
                diagnosticTraceId,
                ImportDiagnosticEvent(phase = "document_prepared", outcome = "success", chunkCount = work.chunks.size),
            )
            if (work.chunks.isEmpty()) {
                diagnostics.record(diagnosticTraceId, ImportDiagnosticEvent(phase = "document_extraction", outcome = "manual", failure = "empty_text"))
                finishCurrent(DocumentStatus.MANUAL, BatchItemStatus.MANUAL, "No se encontró texto; puedes ingresar eventos manualmente.")
                continue
            }
            if (!aiSettings.localAiEnabled && !aiSettings.cloudAiEnabled) {
                // The text is already transcribed for chat grounding, so the file is a valid source
                // even without event extraction — finish as a source rather than "requires manual".
                finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "Guardado como fuente; IA desactivada", 0)
                continue
            }
            if (processCurrent()) return
        }
        completeBatch()
    }

    private suspend fun processCurrent(): Boolean {
        val work = current ?: return false
        val id = batchId ?: return false
        updateItem(work.queued.itemId, BatchItemStatus.WAITING, "Analizando contenido")
        mutableState.update { it.copy(partsDone = 0, partsTotal = 0) }
        val confirmedEvents = events.allEntities().map(EventRepository::toDomain)
        val confirmed = confirmedEvents.map { event ->
            CalendarEventDraft(
                id = event.id,
                title = event.title,
                category = when (event.kind) {
                    EventKind.CLASS -> EventCategory.CLASS
                    EventKind.EXAM -> EventCategory.EXAM
                    EventKind.QUIZ -> EventCategory.QUIZ
                    EventKind.TAREA -> EventCategory.TAREA
                    EventKind.TRANSIT -> EventCategory.TRANSIT
                    EventKind.ACTIVITY -> EventCategory.ACTIVITY
                },
                institution = event.institution,
                date = event.start.toLocalDate(),
                startTime = event.start.toLocalTime(),
                endTime = event.end.toLocalTime(),
                location = event.location,
                course = event.courseCode?.takeIf(String::isNotBlank)?.let { Course(it, null) },
                sourcePage = null,
                evidence = null,
                description = event.notes,
                sourceDocumentId = event.sourceDocumentId,
            )
        }
        val aiSettings = settings.current()
        val localEngine = nano.takeIf { aiSettings.localAiEnabled && !skipNano && !work.skipLocal }
        val cloudEngine = cloudSession.takeIf { aiSettings.cloudAiEnabled }
        val engine = EventExtractionEngine(
            local = localEngine,
            cloud = cloudEngine,
            consent = consent.takeIf { aiSettings.cloudAiEnabled },
            diagnostics = diagnostics,
            diagnosticTraceId = work.diagnosticTraceId,
        )
        val assumedInstitution = state.value.enabledInstitutions.singleOrNull()
        val outcome = try {
            localInferenceActive = localEngine != null
            engine.extract(
                id,
                work.chunks,
                state.value.drafts + confirmed,
                assumedInstitution,
                onProgress = { partsDone, partsTotal ->
                    mutableState.update {
                        it.copy(
                            partsDone = partsDone,
                            partsTotal = partsTotal,
                            message = "Analizando parte $partsDone de $partsTotal…",
                        )
                    }
                },
            )
        } finally {
            localInferenceActive = false
        }
        return when (outcome) {
            is ExtractionOutcome.Drafts -> {
                work.diagnosticBackend = outcome.modelsUsed.firstOrNull()?.let { if (it.startsWith("gemini-nano")) "local" else "cloud" }
                val tagged = outcome.drafts.map { it.copy(sourceDocumentId = work.queued.document.id) }
                val autofilled = ScheduleTimeAutofill.fill(tagged, confirmed, outcome.syllabus?.groups.orEmpty(), outcome.syllabus?.course)
                work.modelsUsed = outcome.modelsUsed
                if (outcome.syllabus != null && outcome.syllabus.canExpandClasses) {
                    val linked = SyllabusClassLinker.link(outcome.syllabus, confirmedEvents)
                    val automaticGroup = linked.uniqueGroup
                    if (automaticGroup != null) {
                        val classDrafts = linked.drafts.ifEmpty {
                            DuplicateDetector.mark(ClassSessionExpander.expand(outcome.syllabus, automaticGroup), state.value.drafts)
                        }.map { it.copy(sourceDocumentId = work.queued.document.id) }
                        val documentDrafts = autofilled + classDrafts
                        documentDrafts.forEach { events.saveDraft(it.toEntity()) }
                        work.documentDrafts = documentDrafts
                        mutableState.update { it.copy(drafts = mergeDrafts(it.drafts, documentDrafts)) }
                        finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "${documentDrafts.size} borradores", documentDrafts.size, outcome.modelsUsed)
                        false
                    } else {
                        autofilled.forEach { events.saveDraft(it.toEntity()) }
                        work.documentDrafts = autofilled
                        mutableState.update { it.copy(drafts = mergeDrafts(it.drafts, autofilled), stage = ImportStage.SELECT_GROUP, syllabus = outcome.syllabus, currentDocumentName = work.queued.document.displayName) }
                        true
                    }
                } else {
                    autofilled.forEach { events.saveDraft(it.toEntity()) }
                    work.documentDrafts = autofilled
                    mutableState.update { it.copy(drafts = mergeDrafts(it.drafts, autofilled)) }
                    finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "${autofilled.size} borradores", autofilled.size, outcome.modelsUsed)
                    false
                }
            }
            is ExtractionOutcome.NeedsDownload -> {
                diagnostics.record(work.diagnosticTraceId, ImportDiagnosticEvent(phase = "routing_decision", backend = "local", outcome = "download_required", capability = outcome.capability.name.lowercase()))
                mutableState.update { it.copy(stage = ImportStage.NEEDS_NANO, nanoCapability = outcome.capability, nanoFailure = null, message = "Gemini Nano necesita descargarse para ${work.queued.document.displayName}.") }
                true
            }
            is ExtractionOutcome.LocalFailed -> {
                work.diagnosticBackend = "local"
                diagnostics.record(work.diagnosticTraceId, ImportDiagnosticEvent(phase = "routing_decision", backend = "local", outcome = "failure", failure = outcome.failure.name.lowercase()))
                mutableState.update {
                    it.copy(
                        stage = ImportStage.LOCAL_ERROR,
                        nanoFailure = outcome.failure,
                        message = nanoFailureMessage(outcome.failure, outcome.retryable),
                    )
                }
                true
            }
            is ExtractionOutcome.NeedsConsent -> {
                work.skipLocal = true
                work.diagnosticBackend = "cloud"
                diagnostics.record(work.diagnosticTraceId, ImportDiagnosticEvent(phase = "cloud_consent_offered", backend = "cloud", outcome = "pending"))
                mutableState.update { it.copy(stage = ImportStage.NEEDS_CONSENT, message = "Se necesita procesamiento opcional en la nube para continuar el lote.") }
                true
            }
            is ExtractionOutcome.CloudFailed -> {
                work.diagnosticBackend = "cloud"
                diagnostics.record(work.diagnosticTraceId, ImportDiagnosticEvent(phase = "routing_decision", backend = "cloud", outcome = "failure", failure = outcome.failure.name.lowercase()))
                val message = cloudMessage(outcome.failure)
                finishCurrent(DocumentStatus.FAILED, BatchItemStatus.FAILED, message)
                false
            }
            is ExtractionOutcome.NoEvents -> {
                work.modelsUsed = outcome.modelsUsed
                finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "Guardado como fuente para el asistente", 0, outcome.modelsUsed)
                false
            }
            is ExtractionOutcome.Manual -> {
                diagnostics.record(work.diagnosticTraceId, ImportDiagnosticEvent(phase = "routing_decision", backend = work.diagnosticBackend, outcome = "manual"))
                finishCurrent(DocumentStatus.MANUAL, BatchItemStatus.MANUAL, outcome.reason)
                false
            }
        }
    }

    fun decideCloud(granted: Boolean) {
        val id = batchId ?: return
        val work = current ?: return
        if (!transitionCloudDecision(granted)) return
        consent.set(id, if (granted) ConsentDecision.GRANTED else ConsentDecision.DENIED)
        updateItem(
            work.queued.itemId,
            BatchItemStatus.WAITING,
            if (granted) "Procesando con Gemini" else "Continuando sin nube",
        )
        batchJob = viewModelScope.launch {
            diagnostics.record(
                work.diagnosticTraceId,
                ImportDiagnosticEvent(phase = "cloud_consent_decided", backend = "cloud", outcome = if (granted) "accepted" else "declined"),
            )
            if (!processCurrent()) processQueue()
        }
    }

    private fun transitionCloudDecision(granted: Boolean): Boolean {
        while (true) {
            val before = mutableState.value
            val after = before.beginCloudDecision(granted) ?: return false
            if (mutableState.compareAndSet(before, after)) return true
        }
    }

    fun downloadNano() {
        batchJob = viewModelScope.launch {
            var downloadFailure: NanoFailureKind? = null
            val capability = try {
                nano.download { download ->
                    when (download) {
                        is NanoDownloadState.Started -> mutableState.update { it.copy(nanoTotalBytes = download.totalBytes, nanoBytes = 0, nanoFailure = null) }
                        is NanoDownloadState.Progress -> mutableState.update { it.copy(nanoBytes = download.downloadedBytes, nanoTotalBytes = download.totalBytes ?: it.nanoTotalBytes) }
                        NanoDownloadState.Completed -> Unit
                        is NanoDownloadState.Failed -> downloadFailure = download.failure
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                downloadFailure = NanoFailureKind.SDK_FAILURE
                NanoCapability.UNAVAILABLE
            }
            if (capability == NanoCapability.AVAILABLE) {
                if (!processCurrent()) processQueue()
            } else {
                val failure = downloadFailure ?: NanoFailureKind.NOT_AVAILABLE
                mutableState.update { it.copy(stage = ImportStage.LOCAL_ERROR, nanoFailure = failure, message = nanoFailureMessage(failure, retryable = true)) }
            }
        }
    }

    fun useCloudInsteadOfNano() {
        viewModelScope.launch {
            if (!settings.current().cloudAiEnabled) {
                useManualEntry()
            } else {
                skipNano = true
                diagnostics.record(
                    current?.diagnosticTraceId,
                    ImportDiagnosticEvent(phase = "cloud_alternative_selected", backend = "cloud", outcome = "selected"),
                )
                if (!processCurrent()) processQueue()
            }
        }
    }

    fun retryNano() {
        val previousFailure = mutableState.value.nanoFailure
        skipNano = false
        current?.skipLocal = false
        mutableState.update { it.copy(stage = ImportStage.EXTRACTING, nanoFailure = null, message = "Reintentando en este dispositivo…") }
        val traceId = current?.diagnosticTraceId
        batchJob = viewModelScope.launch {
            diagnostics.record(traceId, ImportDiagnosticEvent(phase = "nano_retry_requested", backend = "local", outcome = "retry"))
            if (previousFailure == NanoFailureKind.SDK_FAILURE || previousFailure == NanoFailureKind.UNKNOWN) {
                nano.resetForRetry()
            }
            if (!processCurrent()) processQueue()
        }
    }

    fun onImporterBackgrounded() {
        if (!localInferenceActive) return
        batchJob?.cancel()
        val traceId = current?.diagnosticTraceId
        viewModelScope.launch {
            diagnostics.record(traceId, ImportDiagnosticEvent(phase = "foreground_interrupted", backend = "local", outcome = "cancelled", failure = "background_blocked"))
        }
        mutableState.update {
            it.copy(
                stage = ImportStage.LOCAL_ERROR,
                nanoFailure = NanoFailureKind.BACKGROUND_BLOCKED,
                message = nanoFailureMessage(NanoFailureKind.BACKGROUND_BLOCKED, retryable = true),
            )
        }
    }

    private fun resumeCurrent() {
        batchJob = viewModelScope.launch { if (!processCurrent()) processQueue() }
    }

    fun selectGroup(group: CourseGroup) {
        val work = current ?: return
        val syllabus = state.value.syllabus ?: return
        batchJob = viewModelScope.launch {
            val linked = SyllabusClassLinker.link(syllabus, events.allEntities().map(EventRepository::toDomain), group).drafts
            val expanded = (if (linked.isNotEmpty()) linked else DuplicateDetector.mark(ClassSessionExpander.expand(syllabus, group), state.value.drafts))
                .map { it.copy(sourceDocumentId = work.queued.document.id) }
            val autofilled = ScheduleTimeAutofill.fill(work.documentDrafts, listOf(group), syllabus.course)
            val documentDrafts = expanded + autofilled
            documentDrafts.forEach { events.saveDraft(it.toEntity()) }
            mutableState.update { it.copy(drafts = mergeDrafts(it.drafts, documentDrafts), syllabus = null) }
            finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "${documentDrafts.size} borradores", documentDrafts.size, work.modelsUsed)
            processQueue()
        }
    }

    fun skipGroupSelection() {
        val work = current ?: return
        batchJob = viewModelScope.launch {
            mutableState.update { it.copy(syllabus = null) }
            finishCurrent(DocumentStatus.COMPLETED, BatchItemStatus.COMPLETED, "${work.documentDrafts.size} borradores", work.documentDrafts.size, work.modelsUsed)
            processQueue()
        }
    }

    private suspend fun finishCurrent(
        documentStatus: DocumentStatus,
        itemStatus: BatchItemStatus,
        message: String,
        draftCount: Int = 0,
        modelsUsed: Collection<String> = emptyList(),
    ) {
        val work = current ?: return
        library.finishAttempt(work.attemptId, documentStatus, message.takeIf { documentStatus == DocumentStatus.FAILED || documentStatus == DocumentStatus.MANUAL }, draftCount, modelsUsed)
        val model = modelsUsed.firstOrNull()
        val backend = work.diagnosticBackend ?: model?.let { if (it.startsWith("gemini-nano")) "local" else "cloud" }
        diagnostics.finish(work.diagnosticTraceId, documentStatus.name.lowercase(), backend, model, draftCount)
        updateItem(work.queued.itemId, itemStatus, message)
        current = null
        mutableState.update { it.copy(syllabus = null, currentDocumentName = null) }
    }

    private fun completeBatch() {
        val snapshot = state.value
        val failures = snapshot.batchItems.count { it.status == BatchItemStatus.FAILED }
        val manual = snapshot.batchItems.count { it.status == BatchItemStatus.MANUAL }
        val completed = snapshot.batchItems.count { it.status == BatchItemStatus.COMPLETED }
        val details = snapshot.batchItems.filter { it.status == BatchItemStatus.FAILED }.joinToString("\n") { "${it.displayName}: ${it.message.orEmpty()}" }
        val summary = "Lote terminado: $completed procesados, $manual manuales y $failures con error."
        mutableState.update {
            it.copy(
                stage = if (it.drafts.isNotEmpty()) ImportStage.REVIEW else if (manual > 0) ImportStage.MANUAL else ImportStage.IDLE,
                importId = batchId,
                currentDocumentName = null,
                message = if (details.isBlank()) summary else "$summary\n$details",
            )
        }
    }

    private fun updateItem(id: String, status: BatchItemStatus, message: String?) {
        mutableState.update { state ->
            state.copy(batchItems = state.batchItems.map { if (it.id == id) it.copy(status = status, message = message) else it })
        }
    }

    fun requestOpenDocument(documentId: String) {
        viewModelScope.launch {
            val uri = library.contentUri(documentId)
            mutableState.update { it.copy(openUri = uri, message = if (uri == null) "La copia local ya no está disponible." else it.message) }
        }
    }

    fun consumeOpenUri() = mutableState.update { it.copy(openUri = null) }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            runCatching { library.delete(documentId) }.fold(
                onSuccess = { mutableState.update { it.copy(message = "PDF eliminado; tus eventos y borradores se conservaron.") } },
                onFailure = { error -> mutableState.update { it.copy(message = error.message ?: "No se pudo eliminar el PDF.") } },
            )
        }
    }

    fun addManualDraft(institution: Institution? = null) {
        val resolvedInstitution = institution ?: mutableState.value.enabledInstitutions.singleOrNull()
        val draft = CalendarEventDraft(
            id = UUID.randomUUID().toString(), title = null, category = EventCategory.OTHER,
            institution = resolvedInstitution, date = null, startTime = null, endTime = null,
            location = null, course = null, sourcePage = null, evidence = null,
            issues = setOf(ImportIssue.MISSING_DATE, ImportIssue.MISSING_TIME),
        )
        mutableState.update { it.copy(stage = ImportStage.REVIEW, drafts = it.drafts + draft) }
        viewModelScope.launch { events.saveDraft(draft.toEntity()) }
    }

    fun updateDraft(updated: CalendarEventDraft) {
        mutableState.update { current -> current.copy(drafts = current.drafts.map { if (it.id == updated.id) updated else it }) }
        viewModelScope.launch { events.updateDraft(updated.toEntity()) }
    }

    fun discardDraft(id: String) {
        mutableState.update { it.copy(drafts = it.drafts.filterNot { draft -> draft.id == id }) }
        viewModelScope.launch { events.deleteDraft(id) }
    }

    fun discardDrafts(ids: Set<String>) {
        if (ids.isEmpty()) return
        mutableState.update { current ->
            val remaining = current.drafts.filterNot { it.id in ids }
            current.copy(
                stage = if (remaining.isEmpty()) ImportStage.IDLE else ImportStage.REVIEW,
                drafts = remaining,
                message = "Se descartaron ${current.drafts.size - remaining.size} borradores",
            )
        }
        viewModelScope.launch { events.deleteDrafts(ids) }
    }

    private suspend fun persistConfirmed(confirmed: List<CampusEvent>) {
        val appSettings = settings.current()
        confirmed.forEach { event ->
            val persisted = events.confirmedById(event.id)?.let { mergeConfirmedEvent(it, event) } ?: event
            events.savePreservingExistingMetadata(persisted)
            events.deleteDraft(event.id)
            reminders.schedule(persisted, appSettings.remindersEnabled, appSettings.exactReminders)
        }
        onDataChanged()
    }

    fun confirmDraft(id: String) {
        val draft = state.value.drafts.firstOrNull { it.id == id } ?: return
        val event = draftToCampusEvent(draft)
        if (event == null) {
            mutableState.update { it.copy(message = "Completa título, institución, fecha y un rango horario válido") }
            return
        }
        viewModelScope.launch {
            persistConfirmed(listOf(event))
            mutableState.update { current ->
                val remaining = current.drafts.filterNot { it.id == id }
                current.copy(stage = if (remaining.isEmpty()) ImportStage.IDLE else ImportStage.REVIEW, drafts = remaining, message = "Evento confirmado")
            }
        }
    }

    fun confirmDrafts(ids: Set<String>) {
        val selected = state.value.drafts.filter { it.id in ids }
        val ready = selectedDraftEvents(selected, ids)
        if (ready.isEmpty()) {
            mutableState.update { it.copy(message = "Los borradores seleccionados necesitan título, institución, fecha y un rango horario válido") }
            return
        }
        viewModelScope.launch {
            persistConfirmed(ready)
            val confirmedIds = ready.mapTo(mutableSetOf(), CampusEvent::id)
            mutableState.update { current ->
                val remaining = current.drafts.filterNot { it.id in confirmedIds }
                val invalid = selected.size - ready.size
                current.copy(
                    stage = if (remaining.isEmpty()) ImportStage.IDLE else ImportStage.REVIEW,
                    drafts = remaining,
                    message = if (invalid == 0) "${ready.size} eventos confirmados" else "${ready.size} confirmados; $invalid requieren revisión",
                )
            }
        }
    }

    fun confirmAllReady() {
        val ready = state.value.drafts.filter { it.issues.isEmpty() }.mapNotNull(::draftToCampusEvent)
        if (ready.isEmpty()) {
            mutableState.update { it.copy(message = "No hay borradores completos sin observaciones para confirmar") }
            return
        }
        viewModelScope.launch {
            persistConfirmed(ready)
            val confirmedIds = ready.map { it.id }.toSet()
            mutableState.update { current ->
                val remaining = current.drafts.filterNot { it.id in confirmedIds }
                current.copy(stage = if (remaining.isEmpty()) ImportStage.IDLE else ImportStage.REVIEW, drafts = remaining, message = "${ready.size} eventos confirmados")
            }
        }
    }

    fun useManualEntry() {
        val work = current
        if (work == null) {
            mutableState.update { it.copy(stage = ImportStage.MANUAL, message = "Ingresa los datos sin IA") }
            return
        }
        batchJob = viewModelScope.launch {
            finishCurrent(DocumentStatus.MANUAL, BatchItemStatus.MANUAL, "Procesamiento manual solicitado")
            processQueue()
        }
    }

    fun cancel() {
        batchJob?.cancel()
        val work = current
        queue.forEach { queued -> updateItem(queued.itemId, BatchItemStatus.CANCELLED, "Cancelado") }
        queue.clear()
        current = null
        batchId?.let(consent::clear)
        if (work != null) {
            viewModelScope.launch { library.finishAttempt(work.attemptId, DocumentStatus.CANCELLED, "Cancelado") }
            viewModelScope.launch { diagnostics.finish(work.diagnosticTraceId, "cancelled", work.diagnosticBackend) }
            updateItem(work.queued.itemId, BatchItemStatus.CANCELLED, "Cancelado")
        }
        mutableState.update { it.copy(stage = ImportStage.IDLE, currentDocumentName = null, syllabus = null, message = "Importación cancelada; los PDF guardados se conservaron.") }
    }

    override fun onCleared() {
        batchJob?.cancel()
        nano.close()
    }

    private fun cloudMessage(failure: CloudFailure) = when (failure) {
        CloudFailure.MISSING_KEY -> "Configura tu clave de Gemini o continúa manualmente."
        CloudFailure.INVALID_KEY -> "La clave de Gemini no es válida."
        CloudFailure.QUOTA -> "Se alcanzó la cuota en todos los modelos de Gemini."
        CloudFailure.OFFLINE -> "No hay conexión. Continúa manualmente."
        CloudFailure.SERVER -> "Los modelos de Gemini no están disponibles."
        CloudFailure.INVALID_RESPONSE -> "La respuesta no fue válida. Continúa manualmente."
    }

    private fun nanoFailureMessage(failure: NanoFailureKind, retryable: Boolean): String = when (failure) {
        NanoFailureKind.BUSY -> "Gemini Nano está ocupado. ${if (retryable) "Puedes reintentar." else "Intenta de nuevo más tarde."}"
        NanoFailureKind.BACKGROUND_BLOCKED -> "Gemini Nano solo funciona mientras Mi Campus está visible. Vuelve a la app y reintenta."
        NanoFailureKind.BATTERY_QUOTA -> "Android pausó Gemini Nano para proteger la batería. Usa la nube con consentimiento o continúa manualmente."
        NanoFailureKind.NOT_ENOUGH_STORAGE -> "No hay espacio suficiente para Gemini Nano. Libera almacenamiento y reintenta."
        NanoFailureKind.SYSTEM_UPDATE_REQUIRED -> "Actualiza Android y el sistema de Google Play para usar Gemini Nano."
        NanoFailureKind.AICORE_INCOMPATIBLE -> "AICore no está instalado o necesita actualizarse."
        NanoFailureKind.NOT_AVAILABLE -> "Gemini Nano no está disponible en este dispositivo o AICore aún no está listo."
        NanoFailureKind.REQUEST_REJECTED -> "El fragmento no pudo procesarse localmente."
        NanoFailureKind.RESPONSE_REJECTED -> "Gemini Nano no devolvió un borrador válido."
        NanoFailureKind.SDK_FAILURE -> "La API local falló antes de completar la generación. Reintenta en el dispositivo y, si continúa, exporta el diagnóstico desde Ajustes."
        NanoFailureKind.CANCELLED -> "El procesamiento local se canceló."
        NanoFailureKind.UNKNOWN -> "No se pudo completar el procesamiento local."
    }

    private fun mergeDrafts(existing: List<CalendarEventDraft>, incoming: List<CalendarEventDraft>): List<CalendarEventDraft> {
        val replacements = incoming.associateBy(CalendarEventDraft::id)
        return existing.filterNot { it.id in replacements } + incoming
    }
}
