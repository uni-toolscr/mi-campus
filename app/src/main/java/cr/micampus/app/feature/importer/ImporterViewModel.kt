package cr.micampus.app.feature.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.CourseGroup
import cr.micampus.app.core.model.ExtractedSyllabus
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.ai.ClassSessionExpander
import cr.micampus.app.data.ai.DuplicateDetector
import cr.micampus.app.data.ai.ScheduleTimeAutofill
import cr.micampus.app.core.model.ReminderSettings
import cr.micampus.app.data.ai.CloudConsent
import cr.micampus.app.data.ai.CloudFailure
import cr.micampus.app.data.ai.ConsentDecision
import cr.micampus.app.data.ai.EventExtractionEngine
import cr.micampus.app.data.ai.ExtractionOutcome
import cr.micampus.app.data.ai.GeminiCloudEngine
import cr.micampus.app.data.ai.MlKitNanoEngine
import cr.micampus.app.data.ai.NanoCapability
import cr.micampus.app.data.document.DocumentImporter
import cr.micampus.app.data.document.PdfExtractionException
import cr.micampus.app.data.document.TokenChunker
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.data.local.toEntity
import cr.micampus.app.platform.reminders.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID

enum class ImportStage { IDLE, EXTRACTING, NEEDS_NANO, NEEDS_CONSENT, SELECT_GROUP, REVIEW, MANUAL, ERROR }

data class ImporterUiState(
    val stage: ImportStage = ImportStage.IDLE,
    val drafts: List<CalendarEventDraft> = emptyList(),
    val message: String? = null,
    val nanoCapability: NanoCapability? = null,
    val nanoBytes: Long = 0,
    val importId: String? = null,
    val enabledInstitutions: List<Institution> = Institution.values().toList(),
    val syllabus: ExtractedSyllabus? = null,
    val use12hClock: Boolean = false,
)

private class PerImportConsent : CloudConsent {
    private val decisions = mutableMapOf<String, ConsentDecision>()
    override fun decisionForImport(importId: String) = decisions[importId] ?: ConsentDecision.PENDING
    fun set(importId: String, decision: ConsentDecision) { decisions[importId] = decision }
    fun clear(importId: String) { decisions.remove(importId) }
}

class ImporterViewModel(
    private val documents: DocumentImporter,
    private val events: EventRepository,
    private val settings: SettingsStore,
    private val reminders: ReminderScheduler,
    cloud: GeminiCloudEngine,
    private val nano: MlKitNanoEngine = MlKitNanoEngine(),
    private val chunker: TokenChunker = TokenChunker(),
    private val onDataChanged: suspend () -> Unit = {},
) : ViewModel() {
    private val consent = PerImportConsent()
    private val engine = EventExtractionEngine(local = nano, cloud = cloud, consent = consent)
    private val mutableState = MutableStateFlow(ImporterUiState())
    val state: StateFlow<ImporterUiState> = mutableState.asStateFlow()
    private var pendingChunks: List<String> = emptyList()

    init {
        viewModelScope.launch {
            events.draftModels.collect { restored ->
                if (restored.isNotEmpty() && mutableState.value.stage == ImportStage.IDLE) {
                    mutableState.value = ImporterUiState(ImportStage.REVIEW, restored, "Borradores restaurados", use12hClock = mutableState.value.use12hClock)
                }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { appSettings ->
                val enabled = buildList {
                    if (appSettings.ucrEnabled) add(Institution.UCR)
                    if (appSettings.unaEnabled) add(Institution.UNA)
                }.ifEmpty { listOf(Institution.UCR) }
                mutableState.update { it.copy(enabledInstitutions = enabled, use12hClock = appSettings.use12hClock) }
            }
        }
    }

    fun importPdf(uri: Uri) {
        val importId = UUID.randomUUID().toString()
        mutableState.value = ImporterUiState(ImportStage.EXTRACTING, importId = importId, use12hClock = state.value.use12hClock)
        viewModelScope.launch {
            documents.extract(uri).fold(
                onSuccess = { document ->
                    pendingChunks = chunker.chunk(document.pages)
                    if (pendingChunks.isEmpty()) {
                        mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "No se encontró texto. Puedes ingresar el evento manualmente.", importId = importId, use12hClock = state.value.use12hClock)
                    } else if (!settings.current().aiEnabled) {
                        pendingChunks = emptyList()
                        mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "La IA opcional está desactivada. Ingresa los eventos manualmente o actívala en Ajustes.", importId = importId, use12hClock = state.value.use12hClock)
                    } else process(importId)
                },
                onFailure = { error ->
                    pendingChunks = emptyList()
                    val kind = (error as? PdfExtractionException)?.kind?.name ?: "MALFORMED"
                    mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "No se pudo leer el PDF ($kind). Ingresa los eventos manualmente.", importId = importId, use12hClock = state.value.use12hClock)
                },
            )
        }
    }

    fun decideCloud(granted: Boolean) {
        val id = state.value.importId ?: return
        consent.set(id, if (granted) ConsentDecision.GRANTED else ConsentDecision.DENIED)
        viewModelScope.launch { process(id) }
    }

    fun downloadNano() {
        val id = state.value.importId ?: return
        viewModelScope.launch {
            val capability = runCatching { nano.download { bytes -> mutableState.update { it.copy(nanoBytes = bytes) } } }.getOrDefault(NanoCapability.UNAVAILABLE)
            if (capability == NanoCapability.AVAILABLE) process(id)
            else mutableState.update { it.copy(stage = ImportStage.NEEDS_CONSENT, message = "Gemini Nano no está disponible. Puedes usar la nube con consentimiento o continuar manualmente.") }
        }
    }

    private suspend fun process(importId: String) {
        val confirmed = events.futureEntities().map(EventRepository::toDomain).map { event ->
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
            )
        }
        val assumedInstitution = state.value.enabledInstitutions.singleOrNull()
        when (val outcome = engine.extract(importId, pendingChunks, state.value.drafts + confirmed, assumedInstitution)) {
            is ExtractionOutcome.Drafts -> {
                pendingChunks = emptyList()
                val syllabus = outcome.syllabus
                val autofilled = ScheduleTimeAutofill.fill(
                    outcome.drafts,
                    confirmed,
                    syllabus?.groups.orEmpty(),
                    syllabus?.course,
                )
                autofilled.forEach { events.saveDraft(it.toEntity()) }
                if (syllabus != null && syllabus.canExpandClasses) {
                    mutableState.value = ImporterUiState(ImportStage.SELECT_GROUP, autofilled, importId = importId, syllabus = syllabus, enabledInstitutions = state.value.enabledInstitutions, use12hClock = state.value.use12hClock)
                } else {
                    mutableState.value = ImporterUiState(ImportStage.REVIEW, autofilled, importId = importId, enabledInstitutions = state.value.enabledInstitutions, use12hClock = state.value.use12hClock)
                }
            }
            is ExtractionOutcome.NeedsDownload -> mutableState.update { it.copy(stage = ImportStage.NEEDS_NANO, nanoCapability = outcome.capability, message = "Gemini Nano necesita descargarse en el dispositivo.") }
            is ExtractionOutcome.NeedsConsent -> mutableState.update { it.copy(stage = ImportStage.NEEDS_CONSENT, message = "La nube es opcional. Solo se enviará el texto extraído para esta importación.") }
            is ExtractionOutcome.CloudFailed -> {
                pendingChunks = emptyList()
                mutableState.update { it.copy(stage = ImportStage.MANUAL, message = cloudMessage(outcome.failure)) }
            }
            is ExtractionOutcome.Manual -> {
                pendingChunks = emptyList()
                mutableState.update { it.copy(stage = ImportStage.MANUAL, message = outcome.reason) }
            }
        }
    }

    fun addManualDraft(institution: Institution? = null) {
        val resolvedInstitution = institution ?: mutableState.value.enabledInstitutions.singleOrNull()
        val draft = CalendarEventDraft(
            id = UUID.randomUUID().toString(),
            title = null,
            category = EventCategory.OTHER,
            institution = resolvedInstitution,
            date = null,
            startTime = null,
            endTime = null,
            location = null,
            course = null,
            sourcePage = null,
            evidence = null,
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

    fun selectGroup(group: CourseGroup) {
        val syllabus = state.value.syllabus ?: return
        val existing = state.value.drafts
        val expanded = DuplicateDetector.mark(ClassSessionExpander.expand(syllabus, group), existing)
        val autofilled = ScheduleTimeAutofill.fill(existing, listOf(group), syllabus.course)
        viewModelScope.launch {
            expanded.forEach { events.saveDraft(it.toEntity()) }
            autofilled.zip(existing).filter { (updated, original) -> updated != original }
                .forEach { (updated, _) -> events.saveDraft(updated.toEntity()) }
            mutableState.update {
                it.copy(
                    stage = ImportStage.REVIEW,
                    drafts = expanded + autofilled,
                    syllabus = null,
                    message = if (expanded.isEmpty()) "No se generaron clases para ese grupo" else "Se generaron ${expanded.size} clases con sus temas",
                )
            }
        }
    }

    fun skipGroupSelection() {
        mutableState.update { it.copy(stage = ImportStage.REVIEW, syllabus = null) }
    }

    private fun draftToEvent(draft: CalendarEventDraft): CampusEvent? {
        val title = draft.title?.takeIf(String::isNotBlank)
        val date = draft.date
        val start = draft.startTime
        val end = draft.endTime
        val institution = draft.institution
        if (title == null || date == null || start == null || end == null || institution == null || !end.isAfter(start)) return null
        return CampusEvent(
            id = draft.id,
            title = title,
            institution = institution,
            kind = when (draft.category) {
                EventCategory.CLASS -> EventKind.CLASS
                EventCategory.EXAM -> EventKind.EXAM
                EventCategory.QUIZ -> EventKind.QUIZ
                EventCategory.TAREA -> EventKind.TAREA
                EventCategory.TRANSIT -> EventKind.TRANSIT
                else -> EventKind.ACTIVITY
            },
            start = LocalDateTime.of(date, start),
            end = LocalDateTime.of(date, end),
            location = draft.location.orEmpty(),
            notes = draft.description.orEmpty(),
            source = "importado",
            courseCode = draft.course?.code,
        )
    }

    private suspend fun persistConfirmed(confirmed: List<CampusEvent>) {
        val appSettings = settings.current()
        confirmed.forEach { event ->
            events.save(event)
            events.deleteDraft(event.id)
            reminders.schedule(event, ReminderSettings(enabled = appSettings.remindersEnabled), appSettings.exactReminders)
        }
        onDataChanged()
    }

    fun confirmDraft(id: String) {
        val draft = state.value.drafts.firstOrNull { it.id == id } ?: return
        val event = draftToEvent(draft)
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

    fun confirmAllReady() {
        val ready = state.value.drafts.filter { it.issues.isEmpty() }.mapNotNull(::draftToEvent)
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
        pendingChunks = emptyList()
        mutableState.update { it.copy(stage = ImportStage.MANUAL, message = "Ingresa los datos sin IA") }
    }

    fun cancel() {
        state.value.importId?.let(consent::clear)
        pendingChunks = emptyList()
        mutableState.value = ImporterUiState(use12hClock = state.value.use12hClock)
    }

    override fun onCleared() {
        pendingChunks = emptyList()
        nano.close()
    }

    private fun cloudMessage(failure: CloudFailure) = when (failure) {
        CloudFailure.MISSING_KEY -> "Configura tu clave de Gemini o continúa manualmente."
        CloudFailure.INVALID_KEY -> "La clave de Gemini no es válida."
        CloudFailure.QUOTA -> "Se alcanzó la cuota de Gemini. Continúa manualmente."
        CloudFailure.OFFLINE -> "No hay conexión. Continúa manualmente."
        CloudFailure.SERVER -> "Gemini no está disponible. Continúa manualmente."
        CloudFailure.INVALID_RESPONSE -> "La respuesta no fue válida. Continúa manualmente."
    }
}
