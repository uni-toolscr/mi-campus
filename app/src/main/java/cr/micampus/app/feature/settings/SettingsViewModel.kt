package cr.micampus.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.data.ai.ApiKeyStore
import cr.micampus.app.data.ai.LocalPromptEngine
import cr.micampus.app.data.ai.MlKitNanoEngine
import cr.micampus.app.data.ai.NanoCapability
import cr.micampus.app.data.ai.NanoFailureKind
import cr.micampus.app.data.ai.NanoSelfTestResult
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.data.local.EventRepository
import cr.micampus.app.data.diagnostics.DiagnosticsExport
import cr.micampus.app.data.diagnostics.ImportDiagnosticEvent
import cr.micampus.app.data.diagnostics.ImportDiagnosticsRecorder
import cr.micampus.app.data.diagnostics.NoOpImportDiagnostics
import cr.micampus.app.data.moodle.MoodleAccount
import cr.micampus.app.data.moodle.MoodleException
import cr.micampus.app.data.moodle.MoodleFailureKind
import cr.micampus.app.data.moodle.MoodleSyncManager
import cr.micampus.app.core.model.AcademicProgress
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicProgressSnapshot
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.data.banner.AcademicProgressService
import cr.micampus.app.data.banner.AcademicProgressCalculator
import cr.micampus.app.data.banner.BannerException
import cr.micampus.app.data.banner.BannerFailureKind
import cr.micampus.app.data.banner.NoOpAcademicProgressService
import cr.micampus.app.platform.reminders.ReminderScheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keyPresent: Boolean = false,
    val moodle: MoodleSettingsUiState = MoodleSettingsUiState(),
    val progress: AcademicProgressUiState = AcademicProgressUiState.Hidden,
    val progressTerms: List<AcademicTermProgress> = emptyList(),
    val localAiAvailability: LocalAiAvailability = LocalAiAvailability.Checking,
    val diagnosticsAvailable: Boolean = false,
    val diagnosticsExport: DiagnosticsExport? = null,
    val nanoSelfTest: NanoSelfTestUiState = NanoSelfTestUiState.Idle,
    val message: String? = null,
)

sealed interface NanoSelfTestUiState {
    data object Idle : NanoSelfTestUiState
    data object Running : NanoSelfTestUiState
    data class Success(val modelName: String?, val tokenLimit: Int, val inputTokens: Int) : NanoSelfTestUiState
    data class RequiresDownload(val capability: NanoCapability) : NanoSelfTestUiState
    data object Unavailable : NanoSelfTestUiState
    data class Failure(val failure: NanoFailureKind) : NanoSelfTestUiState
}

sealed interface LocalAiAvailability {
    data object Checking : LocalAiAvailability
    data class Available(val modelName: String?) : LocalAiAvailability
    data object Downloadable : LocalAiAvailability
    data object Downloading : LocalAiAvailability
    data object Unavailable : LocalAiAvailability
    data object CheckFailed : LocalAiAvailability
}

internal val LocalAiAvailability.isCompatible: Boolean
    get() = this is LocalAiAvailability.Available || this == LocalAiAvailability.Downloadable || this == LocalAiAvailability.Downloading

internal suspend fun readLocalAiAvailability(nano: LocalPromptEngine): LocalAiAvailability = try {
    when (nano.checkCapability()) {
        NanoCapability.AVAILABLE -> LocalAiAvailability.Available(nano.baseModelName())
        NanoCapability.DOWNLOADABLE -> LocalAiAvailability.Downloadable
        NanoCapability.DOWNLOADING -> LocalAiAvailability.Downloading
        NanoCapability.UNAVAILABLE -> LocalAiAvailability.Unavailable
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    LocalAiAvailability.CheckFailed
}

data class MoodleSettingsUiState(
    val connected: Boolean = false,
    val displayName: String? = null,
    val lastSyncEpoch: Long? = null,
    val busy: Boolean = false,
)

sealed interface AcademicProgressUiState {
    data object Hidden : AcademicProgressUiState
    data object Loading : AcademicProgressUiState
    data class Available(val progress: AcademicProgress) : AcademicProgressUiState
    data class Unavailable(val message: String) : AcademicProgressUiState
    data class StaleError(val progress: AcademicProgress, val message: String) : AcademicProgressUiState
}

private fun AcademicProgressUiState.withFilter(
    snapshot: AcademicProgressSnapshot?,
    savedFilter: AcademicProgressFilter,
): AcademicProgressUiState {
    val source = snapshot ?: return this
    // A removed/obsolete year must not make a healthy cached result look empty.
    val filter = effectiveFilter(source, savedFilter)
    val derived = AcademicProgressCalculator.derive(source, filter)
    return when (this) {
        is AcademicProgressUiState.Available -> AcademicProgressUiState.Available(derived)
        is AcademicProgressUiState.StaleError -> AcademicProgressUiState.StaleError(derived, message)
        else -> this
    }
}

private fun effectiveFilter(snapshot: AcademicProgressSnapshot?, saved: AcademicProgressFilter): AcademicProgressFilter =
    if (snapshot != null && saved.year != null && snapshot.terms.none { it.year == saved.year }) saved.copy(year = null)
    else saved

class SettingsViewModel(
    private val store: SettingsStore,
    private val keys: ApiKeyStore,
    private val events: EventRepository,
    private val reminders: ReminderScheduling,
    private val moodle: MoodleSyncManager,
    private val progressService: AcademicProgressService = NoOpAcademicProgressService,
    private val onSettingsChanged: suspend () -> Unit = {},
    private val nano: LocalPromptEngine = MlKitNanoEngine(),
    private val diagnostics: ImportDiagnosticsRecorder = NoOpImportDiagnostics,
) : ViewModel() {
    private val keyPresent = MutableStateFlow(keys.read() != null)
    private val moodleState = MutableStateFlow(moodle.account().toUiState())
    private val progressSnapshot = MutableStateFlow<AcademicProgressSnapshot?>(progressService.cached())
    private val progressState = MutableStateFlow<AcademicProgressUiState>(
        if (moodle.account() == null) AcademicProgressUiState.Hidden
        else progressSnapshot.value?.let { AcademicProgressUiState.Available(AcademicProgressCalculator.derive(it)) }
            ?: AcademicProgressUiState.Unavailable("Aún no has actualizado tu avance académico"),
    )
    private val message = MutableStateFlow<String?>(null)
    private val localAiAvailability = MutableStateFlow<LocalAiAvailability>(LocalAiAvailability.Checking)
    private val diagnosticsExport = MutableStateFlow<DiagnosticsExport?>(null)
    private val nanoSelfTest = MutableStateFlow<NanoSelfTestUiState>(NanoSelfTestUiState.Idle)
    private val connectionState = combine(moodleState, progressState, store.settings) { moodleUi, progressUi, settings ->
        moodleUi to progressUi.withFilter(progressSnapshot.value, settings.academicProgressFilter)
    }
    private val baseState = combine(store.settings, keyPresent, connectionState, localAiAvailability, message) { settings, hasKey, connection, localAi, text ->
        val effectiveFilter = effectiveFilter(progressSnapshot.value, settings.academicProgressFilter)
        SettingsUiState(
            settings = settings.copy(academicProgressFilter = effectiveFilter),
            keyPresent = hasKey,
            moodle = connection.first,
            progress = connection.second,
            progressTerms = progressSnapshot.value?.terms.orEmpty(),
            localAiAvailability = localAi,
            diagnosticsAvailable = diagnostics.available,
            message = text,
        )
    }
    val state: StateFlow<SettingsUiState> = combine(baseState, diagnosticsExport, nanoSelfTest) { current, export, selfTest ->
        current.copy(diagnosticsExport = export, nanoSelfTest = selfTest)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(diagnosticsAvailable = diagnostics.available),
    )

    fun setInstitutions(ucr: Boolean, una: Boolean) {
        if (!ucr && !una) {
            // Ignore the toggle that would leave the student with no active institution.
            message.value = "Debes mantener al menos una institución activa"
            return
        }
        viewModelScope.launch { store.setOnboarding(true, ucr, una) }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { store.setTheme(mode) }
    fun setUse12hClock(value: Boolean) = viewModelScope.launch { store.setUse12hClock(value); onSettingsChanged() }
    fun setReminders(enabled: Boolean) = viewModelScope.launch {
        store.setReminders(enabled)
        reschedule(enabled, store.current().exactReminders)
    }

    fun setExactReminders(enabled: Boolean) = viewModelScope.launch {
        store.setExactReminders(enabled)
        reschedule(store.current().remindersEnabled, enabled)
    }
    fun setLocalAiEnabled(enabled: Boolean) {
        if (!localAiAvailability.value.isCompatible) return
        viewModelScope.launch { store.setLocalAiEnabled(enabled) }
    }

    fun setCloudAiEnabled(enabled: Boolean) = viewModelScope.launch { store.setCloudAiEnabled(enabled) }
    fun setAcademicProgressFilter(filter: AcademicProgressFilter) = viewModelScope.launch { store.setAcademicProgressFilter(filter) }

    fun refreshLocalAiCapability() {
        localAiAvailability.value = LocalAiAvailability.Checking
        viewModelScope.launch {
            localAiAvailability.value = readLocalAiAvailability(nano)
        }
    }

    fun saveKey(value: String) {
        runCatching { keys.save(value.trim()) }
            .onSuccess { keyPresent.value = true; message.value = "Clave guardada de forma cifrada" }
            .onFailure { message.value = "No se pudo guardar la clave" }
    }

    fun clearKey() {
        keys.clear()
        keyPresent.value = false
        message.value = "Clave eliminada"
    }

    fun connectMoodle(username: String, password: CharArray) {
        if (moodleState.value.busy) {
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            moodleState.value = moodleState.value.copy(busy = true)
            try {
                val report = moodle.connect(username, password)
                message.value = "Aula Virtual conectada: ${report.imported} eventos sincronizados"
                val connectedUserId = moodle.account()?.userId
                moodleState.value = moodle.account().toUiState()
                // Banner is best-effort: Moodle has already committed its own successful connection.
                refreshProgressLocked(username, password, initial = true, expectedMoodleUserId = connectedUserId)
            } catch (error: MoodleException) {
                message.value = error.sessionAwareMessage()
            } catch (_: Exception) {
                message.value = "No se pudo conectar con Aula Virtual"
            } finally {
                password.fill('\u0000')
                moodleState.value = moodle.account().toUiState()
            }
        }
    }

    fun syncMoodle() {
        if (moodleState.value.busy || !moodleState.value.connected) return
        viewModelScope.launch {
            moodleState.value = moodleState.value.copy(busy = true)
            try {
                val report = moodle.sync()
                message.value = "Aula Virtual actualizada: ${report.changed} cambios, ${report.removed} eliminados"
            } catch (error: MoodleException) {
                message.value = error.sessionAwareMessage()
            } catch (_: Exception) {
                message.value = "No se pudo actualizar Aula Virtual"
            } finally {
                moodleState.value = moodle.account().toUiState()
            }
        }
    }

    /**
     * A single `invalidtoken` answer can be UNA's proxy shedding load, so the session is verified
     * before it is treated as expired; cached data always stays on the device.
     */
    private suspend fun MoodleException.sessionAwareMessage(): String {
        if (kind != MoodleFailureKind.INVALID_TOKEN) return userMessage()
        val invalidated = try {
            moodle.handleInvalidToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return if (invalidated) "La sesión de Aula Virtual venció. Vuelve a conectarla en Ajustes; tus datos guardados se conservan."
        else "Aula Virtual rechazó la solicitud temporalmente. Intenta de nuevo en unos minutos."
    }

    fun disconnectMoodle() {
        if (moodleState.value.busy) return
        viewModelScope.launch {
            moodleState.value = moodleState.value.copy(busy = true)
            val disconnected = runCatching { moodle.disconnect() }.isSuccess
            runCatching { progressService.clear() }
            progressSnapshot.value = null
            progressState.value = AcademicProgressUiState.Hidden
            message.value = if (disconnected) "Aula Virtual desconectada" else "No se pudo desconectar Aula Virtual"
            moodleState.value = moodle.account().toUiState()
        }
    }

    /** Credential-prompt initiated only; progress is intentionally never refreshed in background work. */
    fun refreshAcademicProgress(username: String, password: CharArray) {
        if (!moodleState.value.connected || progressState.value == AcademicProgressUiState.Loading) {
            password.fill('\u0000')
            return
        }
        viewModelScope.launch {
            val connectedUserId = moodle.account()?.userId
            try {
                refreshProgressLocked(username, password, initial = false, expectedMoodleUserId = connectedUserId)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private suspend fun refreshProgressLocked(
        username: String,
        password: CharArray,
        initial: Boolean,
        expectedMoodleUserId: Long?,
    ) {
        val cached = progressSnapshot.value
        progressState.value = AcademicProgressUiState.Loading
        try {
            val snapshot = progressService.fetch(username, password)
            if (expectedMoodleUserId == null || moodle.account()?.userId != expectedMoodleUserId) {
                return
            }
            progressService.save(snapshot)
            progressSnapshot.value = snapshot
            progressState.value = AcademicProgressUiState.Available(AcademicProgressCalculator.derive(snapshot))
            if (!initial) message.value = "Avance académico actualizado"
        } catch (error: BannerException) {
            if (moodle.account()?.userId != expectedMoodleUserId) {
                return
            }
            val text = "No se pudo actualizar el avance académico"
            progressState.value = if (error.kind == BannerFailureKind.NO_COMPLETED_RESULTS) {
                AcademicProgressUiState.Unavailable("No hay resultados finales clasificables")
            } else {
                cached?.let { AcademicProgressUiState.StaleError(AcademicProgressCalculator.derive(it), text) } ?: AcademicProgressUiState.Unavailable(text)
            }
            if (!initial) message.value = text
        } catch (_: Exception) {
            if (moodle.account()?.userId != expectedMoodleUserId) {
                return
            }
            val text = "No se pudo actualizar el avance académico"
            progressState.value = cached?.let { AcademicProgressUiState.StaleError(AcademicProgressCalculator.derive(it), text) } ?: AcademicProgressUiState.Unavailable(text)
        }
    }

    fun clearMessage() { message.value = null }

    fun exportDiagnostics() = viewModelScope.launch {
        val export = diagnostics.export()
        diagnosticsExport.value = export
        if (export == null) message.value = "Aún no hay diagnósticos de importación"
    }

    fun consumeDiagnosticsExport() {
        diagnosticsExport.value = null
    }

    fun clearDiagnostics() = viewModelScope.launch {
        diagnostics.clear()
        diagnosticsExport.value = null
        message.value = "Diagnóstico de importación eliminado"
    }

    fun runNanoSelfTest() {
        if (!diagnostics.available || nanoSelfTest.value == NanoSelfTestUiState.Running) return
        val previous = nanoSelfTest.value
        nanoSelfTest.value = NanoSelfTestUiState.Running
        viewModelScope.launch {
            val traceId = diagnostics.beginAttempt(localEnabled = true, cloudEnabled = false)
            nano.setDiagnosticTrace(traceId)
            try {
                diagnostics.record(traceId, ImportDiagnosticEvent(phase = "nano_self_test_started", backend = "local"))
                if (previous is NanoSelfTestUiState.Failure &&
                    (previous.failure == NanoFailureKind.SDK_FAILURE || previous.failure == NanoFailureKind.UNKNOWN)
                ) {
                    nano.resetForRetry()
                }
                when (val result = nano.selfTest()) {
                    is NanoSelfTestResult.Success -> {
                        nanoSelfTest.value = NanoSelfTestUiState.Success(result.modelName, result.tokenLimit, result.inputTokens)
                        localAiAvailability.value = LocalAiAvailability.Available(result.modelName)
                        diagnostics.finish(traceId, "self_test_success", "local", result.modelName)
                    }
                    is NanoSelfTestResult.RequiresDownload -> {
                        nanoSelfTest.value = NanoSelfTestUiState.RequiresDownload(result.capability)
                        localAiAvailability.value = when (result.capability) {
                            NanoCapability.DOWNLOADABLE -> LocalAiAvailability.Downloadable
                            NanoCapability.DOWNLOADING -> LocalAiAvailability.Downloading
                            NanoCapability.AVAILABLE -> LocalAiAvailability.Available(null)
                            NanoCapability.UNAVAILABLE -> LocalAiAvailability.Unavailable
                        }
                        diagnostics.finish(traceId, "self_test_requires_download", "local")
                    }
                    NanoSelfTestResult.Unavailable -> {
                        nanoSelfTest.value = NanoSelfTestUiState.Unavailable
                        localAiAvailability.value = LocalAiAvailability.Unavailable
                        diagnostics.finish(traceId, "self_test_unavailable", "local")
                    }
                    is NanoSelfTestResult.Failure -> {
                        nanoSelfTest.value = NanoSelfTestUiState.Failure(result.failure)
                        diagnostics.finish(traceId, "self_test_failed", "local")
                    }
                }
            } catch (cancelled: CancellationException) {
                diagnostics.finish(traceId, "self_test_cancelled", "local")
                throw cancelled
            } catch (_: Exception) {
                nanoSelfTest.value = NanoSelfTestUiState.Failure(NanoFailureKind.SDK_FAILURE)
                diagnostics.finish(traceId, "self_test_failed", "local")
            } finally {
                nano.setDiagnosticTrace(null)
            }
        }
    }

    override fun onCleared() {
        nano.close()
    }

    private suspend fun reschedule(enabled: Boolean, exact: Boolean) {
        events.futureEntities().map(EventRepository::toDomain).forEach { event ->
            reminders.cancel(event.id)
            if (enabled) reminders.schedule(event, enabled = true, exactRequested = exact)
        }
    }
}

private fun MoodleAccount?.toUiState() = MoodleSettingsUiState(
    connected = this != null,
    displayName = this?.displayName,
    lastSyncEpoch = this?.lastSyncEpoch,
)

private fun MoodleException.userMessage(): String = when (kind) {
    MoodleFailureKind.INVALID_CREDENTIALS -> "Usuario o contraseña incorrectos"
    MoodleFailureKind.SERVICE_UNAVAILABLE -> "UNA no tiene habilitado el servicio móvil de Moodle"
    MoodleFailureKind.INVALID_TOKEN -> "La sesión de Aula Virtual expiró; vuelve a conectar tu cuenta"
    MoodleFailureKind.UNSUPPORTED -> "Aula Virtual no ofrece las funciones necesarias para sincronizar"
    MoodleFailureKind.NETWORK -> "Sin conexión con Aula Virtual"
    MoodleFailureKind.NOT_CONNECTED -> "Aula Virtual no está conectada"
    MoodleFailureKind.SERVER -> "Aula Virtual respondió con un error"
    MoodleFailureKind.INVALID_RESPONSE -> "Aula Virtual devolvió una respuesta no reconocida"
}
