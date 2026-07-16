package cr.micampus.app.feature.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.CalendarEventDraft
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventCategory
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Course
import cr.micampus.app.core.model.ImportIssue
import cr.micampus.app.core.model.Institution
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

enum class ImportStage { IDLE, EXTRACTING, NEEDS_NANO, NEEDS_CONSENT, REVIEW, MANUAL, ERROR }

data class ImporterUiState(
    val stage: ImportStage = ImportStage.IDLE,
    val drafts: List<CalendarEventDraft> = emptyList(),
    val message: String? = null,
    val nanoCapability: NanoCapability? = null,
    val nanoBytes: Long = 0,
    val importId: String? = null,
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
                    mutableState.value = ImporterUiState(ImportStage.REVIEW, restored, "Borradores restaurados")
                }
            }
        }
    }

    fun importPdf(uri: Uri) {
        val importId = UUID.randomUUID().toString()
        mutableState.value = ImporterUiState(ImportStage.EXTRACTING, importId = importId)
        viewModelScope.launch {
            documents.extract(uri).fold(
                onSuccess = { document ->
                    pendingChunks = chunker.chunk(document.pages)
                    if (pendingChunks.isEmpty()) {
                        mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "No se encontró texto. Puedes ingresar el evento manualmente.", importId = importId)
                    } else if (!settings.current().aiEnabled) {
                        pendingChunks = emptyList()
                        mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "La IA opcional está desactivada. Ingresa los eventos manualmente o actívala en Ajustes.", importId = importId)
                    } else process(importId)
                },
                onFailure = { error ->
                    pendingChunks = emptyList()
                    val kind = (error as? PdfExtractionException)?.kind?.name ?: "MALFORMED"
                    mutableState.value = ImporterUiState(ImportStage.MANUAL, message = "No se pudo leer el PDF ($kind). Ingresa los eventos manualmente.", importId = importId)
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
                    EventKind.TRANSIT -> EventCategory.TRANSIT
                    EventKind.ACTIVITY -> EventCategory.ACTIVITY
                },
                institution = event.institution,
                date = event.start.toLocalDate(),
                startTime = event.start.toLocalTime(),
                endTime = event.end.toLocalTime(),
                location = event.location,
                course = event.notes.takeIf(String::isNotBlank)?.let { Course(it, null) },
                sourcePage = null,
                evidence = null,
            )
        }
        when (val outcome = engine.extract(importId, pendingChunks, state.value.drafts + confirmed)) {
            is ExtractionOutcome.Drafts -> {
                pendingChunks = emptyList()
                outcome.drafts.forEach { events.saveDraft(it.toEntity()) }
                mutableState.value = ImporterUiState(ImportStage.REVIEW, outcome.drafts, importId = importId)
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
        val draft = CalendarEventDraft(
            id = UUID.randomUUID().toString(),
            title = null,
            category = EventCategory.OTHER,
            institution = institution,
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

    fun confirmDraft(id: String) {
        val draft = state.value.drafts.firstOrNull { it.id == id } ?: return
        val title = draft.title?.takeIf(String::isNotBlank)
        val date = draft.date
        val start = draft.startTime
        val end = draft.endTime
        val institution = draft.institution
        if (title == null || date == null || start == null || end == null || institution == null || !end.isAfter(start)) {
            mutableState.update { it.copy(message = "Completa título, institución, fecha y un rango horario válido") }
            return
        }
        val event = CampusEvent(
            id = draft.id,
            title = title,
            institution = institution,
            kind = when (draft.category) {
                EventCategory.CLASS -> EventKind.CLASS
                EventCategory.EXAM -> EventKind.EXAM
                EventCategory.TRANSIT -> EventKind.TRANSIT
                else -> EventKind.ACTIVITY
            },
            start = LocalDateTime.of(date, start),
            end = LocalDateTime.of(date, end),
            location = draft.location.orEmpty(),
            notes = draft.course?.code.orEmpty(),
            source = "importado",
        )
        viewModelScope.launch {
            events.save(event)
            events.deleteDraft(id)
            val appSettings = settings.current()
            reminders.schedule(event, ReminderSettings(enabled = appSettings.remindersEnabled), appSettings.exactReminders)
            mutableState.update { current ->
                val remaining = current.drafts.filterNot { it.id == id }
                current.copy(stage = if (remaining.isEmpty()) ImportStage.IDLE else ImportStage.REVIEW, drafts = remaining, message = "Evento confirmado")
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
        mutableState.value = ImporterUiState()
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
