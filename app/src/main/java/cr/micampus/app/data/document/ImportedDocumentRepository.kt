package cr.micampus.app.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import cr.micampus.app.data.local.DocumentImportAttemptEntity
import cr.micampus.app.data.local.EventDao
import cr.micampus.app.data.local.ImportedDocumentDao
import cr.micampus.app.data.local.ImportedDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class DocumentStatus { STORED, PROCESSING, COMPLETED, MANUAL, FAILED, CANCELLED, INTERRUPTED }

data class ImportedDocument(
    val id: String,
    val displayName: String,
    val sha256: String,
    val byteSize: Long,
    val importedAtEpoch: Long,
    val lastProcessedAtEpoch: Long?,
    val status: DocumentStatus,
    val error: String?,
    val draftCount: Int,
    val modelsUsed: List<String>,
)

data class StoredBatchItem(val document: ImportedDocument, val reused: Boolean)
data class RejectedBatchItem(val displayName: String, val reason: String)
data class StoreBatchResult(val stored: List<StoredBatchItem>, val rejected: List<RejectedBatchItem>)

class DocumentStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ImportedDocumentRepository(
    private val context: Context,
    private val documentsDao: ImportedDocumentDao,
    private val eventDao: EventDao,
    private val transcriptionsDao: cr.micampus.app.data.local.DocumentTranscriptionDao? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
    val documents: Flow<List<ImportedDocument>> = documentsDao.documents().map { rows -> rows.map(::toModel) }

    suspend fun storeBatch(uris: List<Uri>): StoreBatchResult = withContext(Dispatchers.IO) {
        if (uris.size > MAX_FILES) {
            return@withContext StoreBatchResult(
                stored = emptyList(),
                rejected = listOf(RejectedBatchItem("Selección", "Puedes importar un máximo de $MAX_FILES PDF a la vez.")),
            )
        }
        val stored = mutableListOf<StoredBatchItem>()
        val rejected = mutableListOf<RejectedBatchItem>()
        var batchBytes = 0L
        uris.forEach { uri ->
            val name = metadata(uri).first
            runCatching {
                val remaining = MAX_BATCH_BYTES - batchBytes
                if (remaining <= 0) throw DocumentStoreException("La selección supera 100 MiB.")
                val item = storeOne(uri, name, minOf(MAX_FILE_BYTES, remaining))
                batchBytes += item.document.byteSize
                stored += item
            }.onFailure { error ->
                rejected += RejectedBatchItem(name, error.message ?: "No se pudo guardar el PDF.")
            }
        }
        StoreBatchResult(stored, rejected)
    }

    suspend fun document(id: String): ImportedDocument? = documentsDao.document(id)?.let(::toModel)

    suspend fun file(id: String): File? = withContext(Dispatchers.IO) {
        documentsDao.document(id)?.let { row -> File(directory, row.localFileName).takeIf(File::isFile) }
    }

    suspend fun contentUri(id: String): Uri? = file(id)?.let { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun startAttempt(documentId: String, batchId: String): String {
        val timestamp = now()
        val attempt = DocumentImportAttemptEntity(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            batchId = batchId,
            startedAtEpoch = timestamp,
        )
        documentsDao.upsertAttempt(attempt)
        documentsDao.document(documentId)?.let {
            documentsDao.upsertDocument(
                it.copy(
                    latestStatus = DocumentStatus.PROCESSING.name,
                    latestError = null,
                    latestModels = null,
                ),
            )
        }
        return attempt.id
    }

    suspend fun finishAttempt(
        attemptId: String,
        status: DocumentStatus,
        error: String? = null,
        draftCount: Int = 0,
        modelsUsed: Collection<String> = emptyList(),
    ) {
        val attempt = documentsDao.attempt(attemptId) ?: return
        val timestamp = now()
        val models = modelsUsed.distinct().joinToString(",").ifBlank { null }
        documentsDao.upsertAttempt(
            attempt.copy(
                finishedAtEpoch = timestamp,
                status = status.name,
                error = error,
                draftCount = draftCount,
                modelsUsed = models,
            ),
        )
        documentsDao.document(attempt.documentId)?.let {
            documentsDao.upsertDocument(
                it.copy(
                    lastProcessedAtEpoch = timestamp,
                    latestStatus = status.name,
                    latestError = error,
                    latestDraftCount = draftCount,
                    latestModels = models,
                ),
            )
        }
    }

    suspend fun markInterrupted() {
        val timestamp = now()
        documentsDao.interruptProcessing(timestamp)
        documentsDao.interruptDocuments(timestamp)
    }

    suspend fun delete(documentId: String) = withContext(Dispatchers.IO) {
        val row = documentsDao.document(documentId) ?: return@withContext
        val localFile = File(directory, row.localFileName)
        if (localFile.exists() && !localFile.delete()) throw DocumentStoreException("No se pudo eliminar la copia local del PDF.")
        eventDao.clearConfirmedDocument(documentId)
        eventDao.clearDraftDocument(documentId)
        transcriptionsDao?.deleteForDocument(documentId)
        documentsDao.deleteAttempts(documentId)
        documentsDao.deleteDocument(documentId)
    }

    private suspend fun storeOne(uri: Uri, displayName: String, allowedBytes: Long): StoredBatchItem {
        val temporary = File(directory, ".${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw DocumentStoreException("No se pudo abrir el PDF.")
            input.use { source ->
                FileOutputStream(temporary).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > allowedBytes) {
                            val reason = if (allowedBytes < MAX_FILE_BYTES) "La selección supera 100 MiB." else "El PDF supera 25 MiB."
                            throw DocumentStoreException(reason)
                        }
                        digest.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                    }
                    target.fd.sync()
                }
            }
            if (count == 0L) throw DocumentStoreException("El archivo está vacío.")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val existing = documentsDao.documentByHash(sha256)
            val destination = File(directory, "$sha256.${extensionFor(uri, displayName)}")
            if (!destination.isFile) {
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            } else temporary.delete()
            val row = existing?.copy(displayName = displayName)
                ?: ImportedDocumentEntity(
                    id = UUID.randomUUID().toString(),
                    displayName = displayName,
                    sha256 = sha256,
                    byteSize = count,
                    localFileName = destination.name,
                    importedAtEpoch = now(),
                )
            documentsDao.upsertDocument(row)
            return StoredBatchItem(toModel(row), reused = existing != null)
        } catch (error: SecurityException) {
            throw DocumentStoreException("No hay permiso para leer este PDF.", error)
        } catch (error: IOException) {
            throw if (error is DocumentStoreException) error else DocumentStoreException("No se pudo copiar el PDF al almacenamiento local.", error)
        } finally {
            temporary.delete()
        }
    }

    /** Preserves the original file extension so the stored copy opens with the right viewer. */
    private fun extensionFor(uri: Uri, displayName: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotBlank() && it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        val fromMime = context.contentResolver.getType(uri)
            ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromName ?: fromMime ?: "pdf"
    }

    private fun metadata(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index -> if (!cursor.isNull(index)) size = cursor.getLong(index) }
                }
            }
        }
        return (name?.takeIf(String::isNotBlank) ?: "documento.pdf") to size
    }

    private fun toModel(row: ImportedDocumentEntity) = ImportedDocument(
        id = row.id,
        displayName = row.displayName,
        sha256 = row.sha256,
        byteSize = row.byteSize,
        importedAtEpoch = row.importedAtEpoch,
        lastProcessedAtEpoch = row.lastProcessedAtEpoch,
        status = runCatching { DocumentStatus.valueOf(row.latestStatus) }.getOrDefault(DocumentStatus.STORED),
        error = row.latestError,
        draftCount = row.latestDraftCount,
        modelsUsed = row.latestModels.orEmpty().split(',').filter(String::isNotBlank),
    )

    companion object {
        const val MAX_FILES = 10
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        const val MAX_BATCH_BYTES = 100L * 1024 * 1024
        private const val DIRECTORY = "imported_pdfs"
    }
}
