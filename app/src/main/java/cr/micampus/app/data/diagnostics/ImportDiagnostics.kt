package cr.micampus.app.data.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ImportDiagnosticEvent(
    val timestampEpoch: Long = 0,
    val phase: String,
    val operation: String? = null,
    val backend: String? = null,
    val outcome: String? = null,
    val model: String? = null,
    val capability: String? = null,
    val failure: String? = null,
    val httpStatus: Int? = null,
    val retryIndex: Int? = null,
    val chunkIndex: Int? = null,
    val chunkCount: Int? = null,
    val inputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val tokenLimit: Int? = null,
    val inputCharacters: Int? = null,
    val inputWords: Int? = null,
    val exceptionType: String? = null,
    val genAiErrorCode: Int? = null,
    val splitDepth: Int? = null,
    val elapsedMillis: Long? = null,
    val localEnabled: Boolean? = null,
    val cloudEnabled: Boolean? = null,
    val draftCount: Int? = null,
)

internal fun ImportDiagnosticEvent.sanitized(): ImportDiagnosticEvent = copy(
    operation = operation?.let { if (it in SAFE_DIAGNOSTIC_OPERATIONS) it else "other" },
    exceptionType = exceptionType?.let { if (it in SAFE_EXCEPTION_TYPES) it else "OtherException" },
    inputCharacters = inputCharacters?.coerceAtLeast(0),
    inputWords = inputWords?.coerceAtLeast(0),
)

private val SAFE_EXCEPTION_TYPES = setOf(
    "GenAiException",
    "IllegalArgumentException",
    "IllegalStateException",
    "SecurityException",
    "IOException",
    "OtherException",
)

private val SAFE_DIAGNOSTIC_OPERATIONS = setOf(
    "check_status",
    "get_base_model_name",
    "download",
    "warmup",
    "build_request",
    "get_token_limit",
    "count_tokens",
    "generate_content",
    "process_response",
    "token_probe_build_request",
    "token_probe_count_tokens",
    "self_test_build_request",
    "self_test_get_token_limit",
    "self_test_count_tokens",
    "self_test_generate_content",
    "self_test_process_response",
    "recreate_client",
)

data class DiagnosticTrace(
    val traceId: String,
    val startedAtEpoch: Long,
    val completed: Boolean = false,
    val events: List<ImportDiagnosticEvent> = emptyList(),
)

data class DiagnosticsExport(val uri: Uri, val fileName: String)

interface ImportDiagnosticsRecorder {
    val available: Boolean
    suspend fun beginAttempt(localEnabled: Boolean, cloudEnabled: Boolean): String
    suspend fun record(traceId: String?, event: ImportDiagnosticEvent)
    suspend fun finish(
        traceId: String?,
        outcome: String,
        backend: String? = null,
        model: String? = null,
        draftCount: Int = 0,
    )
    suspend fun export(): DiagnosticsExport?
    suspend fun clear()
}

object NoOpImportDiagnostics : ImportDiagnosticsRecorder {
    override val available = false
    override suspend fun beginAttempt(localEnabled: Boolean, cloudEnabled: Boolean) = ""
    override suspend fun record(traceId: String?, event: ImportDiagnosticEvent) = Unit
    override suspend fun finish(traceId: String?, outcome: String, backend: String?, model: String?, draftCount: Int) = Unit
    override suspend fun export(): DiagnosticsExport? = null
    override suspend fun clear() = Unit
}

internal class DiagnosticBuffer(
    traces: List<DiagnosticTrace> = emptyList(),
    private val maxTraces: Int = 10,
    private val maxEventsPerTrace: Int = 200,
) {
    private val items = traces.toMutableList()

    init {
        prune()
    }

    fun begin(traceId: String, timestamp: Long, localEnabled: Boolean, cloudEnabled: Boolean) {
        items += DiagnosticTrace(
            traceId = traceId,
            startedAtEpoch = timestamp,
            events = listOf(
                ImportDiagnosticEvent(
                    timestampEpoch = timestamp,
                    phase = "attempt_started",
                    localEnabled = localEnabled,
                    cloudEnabled = cloudEnabled,
                ),
            ),
        )
        prune()
    }

    fun append(traceId: String, event: ImportDiagnosticEvent) {
        val index = items.indexOfLast { it.traceId == traceId }
        if (index < 0) return
        val trace = items[index]
        val combined = trace.events + event
        val retained = if (combined.size <= maxEventsPerTrace) {
            combined
        } else {
            listOf(combined.first()) + combined.takeLast(maxEventsPerTrace - 1)
        }
        items[index] = trace.copy(events = retained)
    }

    fun complete(traceId: String, event: ImportDiagnosticEvent) {
        append(traceId, event)
        val index = items.indexOfLast { it.traceId == traceId }
        if (index >= 0) items[index] = items[index].copy(completed = true)
        prune()
    }

    fun snapshot(): List<DiagnosticTrace> = items.toList()
    fun trace(traceId: String): DiagnosticTrace? = items.lastOrNull { it.traceId == traceId }
    fun clear() = items.clear()

    private fun prune() {
        val tracesToKeep = items.sortedByDescending(DiagnosticTrace::startedAtEpoch)
            .take(maxTraces)
            .mapTo(mutableSetOf(), DiagnosticTrace::traceId)
        items.removeAll { it.traceId !in tracesToKeep }
    }
}

class FileImportDiagnostics(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : ImportDiagnosticsRecorder {
    override val available = true
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val mutex = Mutex()
    private val root = File(appContext.noBackupFilesDir, "diagnostics").apply { mkdirs() }
    private val storeFile = File(root, "import-traces.json")
    private val exportDirectory = File(root, "exports").apply { mkdirs() }
    private val buffer = DiagnosticBuffer(loadTraces())

    override suspend fun beginAttempt(localEnabled: Boolean, cloudEnabled: Boolean): String = mutex.withLock {
        val traceId = UUID.randomUUID().toString()
        buffer.begin(traceId, now(), localEnabled, cloudEnabled)
        persistLocked()
        traceId
    }

    override suspend fun record(traceId: String?, event: ImportDiagnosticEvent) {
        if (traceId.isNullOrBlank()) return
        mutex.withLock {
            buffer.append(traceId, event.copy(timestampEpoch = now()).sanitized())
            persistLocked()
        }
    }

    override suspend fun finish(traceId: String?, outcome: String, backend: String?, model: String?, draftCount: Int) {
        if (traceId.isNullOrBlank()) return
        mutex.withLock {
            val elapsedMillis = buffer.trace(traceId)?.let { (now() - it.startedAtEpoch).coerceAtLeast(0) }
            buffer.complete(
                traceId,
                ImportDiagnosticEvent(
                    timestampEpoch = now(),
                    phase = "attempt_finished",
                    backend = backend,
                    outcome = outcome,
                    model = model,
                    draftCount = draftCount,
                    elapsedMillis = elapsedMillis,
                ),
            )
            persistLocked()
        }
    }

    override suspend fun export(): DiagnosticsExport? = mutex.withLock {
        val traces = buffer.snapshot()
        if (traces.isEmpty()) return@withLock null
        withContext(Dispatchers.IO) {
            exportDirectory.listFiles()?.forEach(File::delete)
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(now()))
            val file = File(exportDirectory, "micampus-diagnostics-$timestamp.zip")
            val events = traces.flatMap { trace ->
                trace.events.map { event -> gson.toJson(mapOf("traceId" to trace.traceId, "event" to event)) }
            }.joinToString("\n", postfix = "\n")
            writeDiagnosticsArchive(file, summary(traces), events, PRIVACY_NOTICE)
            DiagnosticsExport(
                uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file),
                fileName = file.name,
            )
        }
    }

    override suspend fun clear() = mutex.withLock {
        buffer.clear()
        withContext(Dispatchers.IO) {
            storeFile.delete()
            exportDirectory.listFiles()?.forEach(File::delete)
            Unit
        }
    }

    private fun loadTraces(): List<DiagnosticTrace> = runCatching {
        if (!storeFile.isFile) return@runCatching emptyList()
        val type = object : TypeToken<List<DiagnosticTrace>>() {}.type
        gson.fromJson<List<DiagnosticTrace>>(storeFile.readText(), type).orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun persistLocked() = withContext(Dispatchers.IO) {
        val temporary = File(root, ".import-traces-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(gson.toJson(buffer.snapshot()))
            if (!temporary.renameTo(storeFile)) {
                temporary.copyTo(storeFile, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun summary(traces: List<DiagnosticTrace>): String {
        val packageInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        val aiCoreVersion = runCatching {
            appContext.packageManager.getPackageInfo("com.google.android.aicore", 0).versionName
        }.getOrNull() ?: "not-reported"
        val completed = traces.count(DiagnosticTrace::completed)
        @Suppress("DEPRECATION")
        val versionCode = packageInfo?.let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        } ?: 0
        return buildString {
            appendLine("Mi Campus import diagnostics")
            appendLine("Generated UTC: ${Instant.ofEpochMilli(now())}")
            appendLine("App: ${packageInfo?.versionName ?: "unknown"} ($versionCode)")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("AICore: $aiCoreVersion")
            appendLine("ML Kit Prompt: 1.0.0-beta2")
            appendLine("Traces: ${traces.size} ($completed completed)")
            traces.sortedBy(DiagnosticTrace::startedAtEpoch).forEach { trace ->
                val started = trace.events.firstOrNull { it.phase == "attempt_started" }
                val final = trace.events.lastOrNull { it.phase == "attempt_finished" }
                appendLine(
                    "- ${trace.traceId}: ${final?.outcome ?: "active"}; " +
                        "local=${started?.localEnabled}; cloud=${started?.cloudEnabled}; " +
                        "backend=${final?.backend ?: "none"}; model=${final?.model ?: "none"}; " +
                        "elapsedMs=${final?.elapsedMillis ?: "active"}",
                )
            }
        }
    }

    private companion object {
        val PRIVACY_NOTICE = """
            This diagnostic archive intentionally excludes:
            - PDF names, paths, hashes, bytes, and extracted text
            - prompts, model responses, evidence, and calendar data
            - API keys, credentials, exception messages, and stack traces
            Only allowlisted exception categories, ML Kit error codes, operation names, and numeric input sizes are retained.
            Trace identifiers are random and are not document or database identifiers.
        """.trimIndent() + "\n"
    }
}

internal fun writeDiagnosticsArchive(file: File, summary: String, events: String, privacy: String) {
    ZipOutputStream(FileOutputStream(file)).use { zip ->
        zip.writeTextEntry("summary.txt", summary)
        zip.writeTextEntry("events.jsonl", events)
        zip.writeTextEntry("privacy.txt", privacy)
    }
}

private fun ZipOutputStream.writeTextEntry(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}
