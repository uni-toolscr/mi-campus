package cr.micampus.app.data.moodle

import android.content.Context
import android.os.StatFs
import cr.micampus.app.data.local.MoodleContentDao
import cr.micampus.app.data.local.MoodleContentSyncStateEntity
import cr.micampus.app.data.local.MoodleCourseEntity
import cr.micampus.app.data.local.MoodleFileEntity
import cr.micampus.app.data.local.MoodleResourceEntity
import cr.micampus.app.data.local.MoodleSectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Input to persistence. URLs are canonicalised before they can reach Room. */
data class MoodleStoredCatalog(
    val courses: List<MoodleCourseEntity>,
    val sections: List<MoodleSectionEntity>,
    val resources: List<MoodleResourceEntity>,
    val files: List<MoodleFileEntity>,
)

data class MoodleCachedCatalog(
    val courses: List<MoodleCourseEntity>,
    val sections: List<MoodleSectionEntity>,
    val resources: List<MoodleResourceEntity>,
    val files: List<MoodleFileEntity>,
    val sync: MoodleContentSyncStateEntity?,
    val starredFileIds: Set<String> = emptySet(),
)

data class MoodleFileDownloadProgress(val fileId: String, val bytesDownloaded: Long, val totalBytes: Long?)

/**
 * Local source of truth for Contenidos. It deliberately does not depend on the REST DTOs;
 * MoodleClient converts its API output into [MoodleStoredCatalog] before calling [replaceCatalog].
 */
interface MoodleContentSyncStore {
    fun syncState(account: MoodleAccount): Flow<MoodleContentSyncStateEntity?>
    suspend fun replaceCatalog(catalog: MoodleContentCatalog.Available)
    suspend fun markUnsupported(account: MoodleAccount)
    suspend fun markRefreshFailure(account: MoodleAccount, message: String)
    suspend fun clearAccount(account: MoodleAccount): Boolean
}

class MoodleContentRepository(
    private val context: Context,
    private val dao: MoodleContentDao,
    private val baseUrl: String = MoodleClient.BASE_URL,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MoodleContentSyncStore {
    private val refreshMutex = Mutex()
    private val _downloadProgress = MutableStateFlow<Map<String, MoodleFileDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, MoodleFileDownloadProgress>> = _downloadProgress

    fun catalog(account: MoodleAccount): Flow<MoodleCachedCatalog> = catalog(accountId(account))
    fun catalog(accountId: String): Flow<MoodleCachedCatalog> = combine(
        dao.courses(accountId), dao.sections(accountId), dao.resources(accountId), dao.files(accountId), dao.syncState(accountId),
    ) { courses, sections, resources, files, sync -> MoodleCachedCatalog(courses, sections, resources, files, sync) }
        .combine(dao.starredFileIds(accountId)) { catalog, starredFileIds ->
            catalog.copy(starredFileIds = starredFileIds.toSet())
        }

    override fun syncState(account: MoodleAccount): Flow<MoodleContentSyncStateEntity?> = dao.syncState(accountId(account))
    fun downloadedFile(account: MoodleAccount, fileId: String): Flow<File?> = dao.files(accountId(account)).map { files ->
        files.firstOrNull { it.fileId == fileId }?.localFileName?.let(::File)?.takeIf(File::isFile)
    }
    suspend fun localFile(account: MoodleAccount, fileId: String): File? = withContext(Dispatchers.IO) {
        dao.file(accountId(account), fileId)?.localFileName?.let(::File)?.takeIf(File::isFile)
    }

    /** Updates a star only for a file currently present in this account's catalogue. */
    suspend fun setFileStarred(account: MoodleAccount, fileId: String, starred: Boolean) {
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val id = accountId(account)
                if (starred) dao.insertStarIfFileExists(id, fileId) else dao.deleteStar(id, fileId)
            }
        }
    }

    /** Full snapshot replacement. Existing downloaded bytes survive only when identity and remote revision are unchanged. */
    suspend fun replaceCatalog(account: MoodleAccount, catalog: MoodleStoredCatalog) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val id = accountId(account)
            val safe = catalog.canonicalizedFor(id)
            val removed = dao.replaceCatalog(
                accountId = id,
                courses = safe.courses,
                sections = safe.sections,
                resources = safe.resources,
                files = safe.files,
                syncState = MoodleContentSyncStateEntity(id, lastSuccessfulSyncEpoch = clock(), lastAttemptEpoch = clock(), contentsSupported = true),
            )
            removed.forEach { deleteLocalFile(it) }
        }
    }

    /** Adapter from the platform-neutral model returned by [MoodleClient]. */
    override suspend fun replaceCatalog(catalog: MoodleContentCatalog.Available) {
        val account = catalog.account
        val accountId = accountId(account)
        val courses = catalog.courses.map { course ->
            MoodleCourseEntity(accountId, course.remoteId, course.name, course.code, course.remoteOrder)
        }
        val sections = catalog.courses.flatMap { course -> course.sections.map { section ->
            MoodleSectionEntity(accountId, course.remoteId, section.remoteId ?: stableSectionId(section.id), section.name, section.remoteOrder)
        } }
        val resources = catalog.courses.flatMap { course -> course.sections.flatMap { section -> section.resources.map { resource ->
            MoodleResourceEntity(accountId, resource.remoteId, course.remoteId, section.remoteId ?: stableSectionId(section.id), resource.kind.name, resource.name, resource.url, resource.enabled, resource.availabilityMessage, resource.remoteOrder)
        } } }
        val files = catalog.courses.flatMap { course -> course.sections.flatMap { section -> section.resources.flatMap { resource -> resource.files.map { file ->
            MoodleFileEntity(accountId, file.id, resource.remoteId, file.name, file.path, file.url, file.mimeType, file.sizeBytes ?: -1L, file.modifiedEpochSeconds, null, 0, null)
        } } } }
        replaceCatalog(account, MoodleStoredCatalog(courses, sections, resources, files))
    }

    override suspend fun markUnsupported(account: MoodleAccount) = updateState(account, supported = false, error = null)
    override suspend fun markRefreshFailure(account: MoodleAccount, message: String) = updateState(account, supported = null, error = message.take(1_000))

    private suspend fun updateState(account: MoodleAccount, supported: Boolean?, error: String?) {
        val id = accountId(account)
        val old = dao.syncStateNow(id)
        dao.upsertSyncState(
            MoodleContentSyncStateEntity(
                accountId = id,
                lastSuccessfulSyncEpoch = old?.lastSuccessfulSyncEpoch,
                lastAttemptEpoch = clock(),
                lastError = error,
                contentsSupported = supported ?: old?.contentsSupported,
            ),
        )
    }

    /** Deletes Room metadata and all account-private bytes. Call for disconnect and before saving a different account. */
    override suspend fun clearAccount(account: MoodleAccount) = clearAccountId(accountId(account))
    suspend fun clearAccountId(accountId: String) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            dao.clearAccount(accountId).forEach(::deleteLocalFile)
            accountDirectory(accountId).deleteRecursively()
        }
    }

    suspend fun deleteDownload(account: MoodleAccount, fileId: String) = withContext(Dispatchers.IO) {
        val id = accountId(account)
        dao.file(id, fileId)?.let { file -> deleteLocalFile(file); dao.clearDownload(id, fileId) }
    }

    suspend fun deleteAllDownloads(account: MoodleAccount, courseId: Long? = null) = withContext(Dispatchers.IO) {
        val id = accountId(account)
        val resourceIds = if (courseId == null) null else dao.resourcesNow(id).filter { it.courseId == courseId }.map { it.resourceId }.toSet()
        dao.filesNow(id).filter { resourceIds == null || it.resourceId in resourceIds }.forEach { file ->
            deleteLocalFile(file); dao.clearDownload(id, file.fileId)
        }
    }

    /** Secure, cancellable streaming download. The token is only appended to the in-memory request URL. */
    suspend fun download(account: MoodleAccount, fileId: String): File = withContext(Dispatchers.IO) {
        val id = accountId(account)
        val entry = dao.file(id, fileId) ?: throw IllegalArgumentException("Archivo no encontrado")
        entry.localFileName?.let(::File)?.takeIf(File::isFile)?.let { return@withContext it }
        val remote = authenticatedDownloadUrl(entry.url, account.token, baseUrl)
        val dir = accountDirectory(id).apply { mkdirs() }
        val destination = File(dir, localName(entry))
        val part = File(dir, "${destination.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (remote.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "MiCampus Android/1.0")
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Moodle devolvió HTTP $status")
            val total = connection.contentLengthLong.takeIf { it >= 0 } ?: entry.byteSize.takeIf { it >= 0 }
            val required = total ?: 0L
            if (StatFs(dir.absolutePath).availableBytes < required + MIN_FREE_BYTES) throw IOException("No hay espacio suficiente para descargar el archivo")
            var written: Long
            connection.inputStream.use { input -> FileOutputStream(part).use { output ->
                written = copyCancellable(input, output) { count, bytesWritten ->
                    if (StatFs(dir.absolutePath).availableBytes - count < MIN_FREE_BYTES) throw IOException("No hay espacio suficiente para descargar el archivo")
                    _downloadProgress.value = _downloadProgress.value + (fileId to MoodleFileDownloadProgress(fileId, bytesWritten, total))
                }
                output.fd.sync()
            } }
            if (total != null && written != total) throw IOException("La descarga quedó incompleta")
            refreshMutex.withLock {
                val current = dao.file(id, fileId)
                if (current == null ||
                    current.url != entry.url ||
                    current.byteSize != entry.byteSize ||
                    current.modifiedEpoch != entry.modifiedEpoch
                ) {
                    part.delete()
                    destination.delete()
                    throw IOException("El contenido cambió durante la descarga")
                }
                accountDirectory(id).mkdirs()
                atomicMove(part, destination)
                dao.upsertFiles(listOf(current.copy(localFileName = destination.absolutePath, downloadedBytes = written, downloadedAtEpoch = clock())))
            }
            destination
        } finally {
            connection?.disconnect()
            if (part.exists()) part.delete()
            _downloadProgress.value = _downloadProgress.value - fileId
        }
    }

    private fun MoodleStoredCatalog.canonicalizedFor(accountId: String) = copy(
        courses = courses.map { it.copy(accountId = accountId) },
        sections = sections.map { it.copy(accountId = accountId) },
        resources = resources.map { it.copy(accountId = accountId, url = it.url?.let(::canonicalUrl)) },
        files = files.map { it.copy(accountId = accountId, url = canonicalUrl(it.url), localFileName = null, downloadedBytes = 0, downloadedAtEpoch = null) },
    )

    private fun accountDirectory(accountId: String) = File(context.noBackupFilesDir, "moodle_downloads/${sha256(accountId).take(24)}")
    private fun deleteLocalFile(file: MoodleFileEntity) { file.localFileName?.let(::File)?.takeIf(File::exists)?.delete() }
    private fun localName(file: MoodleFileEntity): String {
        val extension = file.fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(12)
        return sha256(file.fileId).take(48) + if (extension.isBlank()) "" else ".${extension}"
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val MIN_FREE_BYTES = 5L * 1024 * 1024
        fun accountId(account: MoodleAccount): String = "una:${account.userId}"
        fun canonicalUrl(url: String): String {
            val uri = URI(url)
            require(uri.scheme.equals("https", true)) { "La URL debe usar HTTPS" }
            val cleanQuery = uri.rawQuery.orEmpty().split('&').filter { part ->
                val name = part.substringBefore('=').lowercase()
                name != "token" && name != "wstoken"
            }.takeIf { it.isNotEmpty() }?.joinToString("&")
            return rawUri(uri.scheme, uri.rawAuthority, uri.rawPath, cleanQuery).toASCIIString()
        }
        internal fun authenticatedDownloadUrl(url: String, token: String, baseUrl: String = MoodleClient.BASE_URL): URI {
            val canonical = URI(canonicalUrl(url))
            val expected = URI(baseUrl)
            require(
                canonical.scheme.equals("https", true) &&
                    canonical.host.equals(expected.host, true) &&
                    canonical.userInfo == null &&
                    canonical.port in listOf(-1, 443),
            ) { "URL de descarga no permitida" }
            val path = when {
                canonical.rawPath.startsWith("/webservice/pluginfile.php/") -> canonical.rawPath
                canonical.rawPath.startsWith("/pluginfile.php/") -> canonical.rawPath.replaceFirst("/pluginfile.php/", "/webservice/pluginfile.php/")
                else -> throw IllegalArgumentException("URL de archivo de Moodle no permitida")
            }
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name()).replace("+", "%20")
            val query = listOfNotNull(canonical.rawQuery?.takeIf(String::isNotBlank), "token=$encodedToken").joinToString("&")
            return rawUri(canonical.scheme, canonical.rawAuthority, path, query)
        }
        internal suspend fun copyCancellable(
            input: InputStream,
            output: OutputStream,
            onChunk: (count: Int, bytesWritten: Long) -> Unit = { _, _ -> },
        ): Long {
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                currentCoroutineContext().ensureActive()
                output.write(buffer, 0, count)
                written += count
                onChunk(count, written)
            }
            return written
        }
        private fun rawUri(scheme: String, rawAuthority: String, rawPath: String, rawQuery: String?): URI {
            val value = buildString {
                append(scheme)
                append("://")
                append(rawAuthority)
                append(rawPath)
                if (!rawQuery.isNullOrBlank()) {
                    append('?')
                    append(rawQuery)
                }
            }
            return URI.create(value)
        }
        private fun atomicMove(from: File, to: File) {
            runCatching { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun stableSectionId(id: String): Long = sha256(id).take(15).toLong(16)
    }
}
