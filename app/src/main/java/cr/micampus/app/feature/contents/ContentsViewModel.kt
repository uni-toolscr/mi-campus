package cr.micampus.app.feature.contents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.core.model.ResourceKind
import cr.micampus.app.core.model.isDecorativeImageFile
import cr.micampus.app.data.moodle.MoodleAccount
import cr.micampus.app.data.moodle.MoodleCachedCatalog
import cr.micampus.app.data.moodle.MoodleContentRepository
import cr.micampus.app.data.moodle.MoodleException
import cr.micampus.app.data.moodle.MoodleFailureKind
import cr.micampus.app.data.moodle.MoodleFileDownloadProgress
import cr.micampus.app.data.moodle.MoodleSyncManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContentFileUi(
    val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val downloaded: Boolean,
    val downloadedBytes: Long,
    val starred: Boolean = false,
)

data class ContentResourceUi(
    val id: Long,
    val name: String,
    val kind: ResourceKind,
    val url: String?,
    val enabled: Boolean,
    val availability: String?,
    val files: List<ContentFileUi>,
)

data class ContentSectionUi(
    val id: Long,
    val name: String,
    val resources: List<ContentResourceUi>,
) {
    val resourceCount: Int get() = resources.size + resources.sumOf { it.files.size }
}

data class ContentCourseUi(
    val id: Long,
    val name: String,
    val code: String?,
    val sections: List<ContentSectionUi>,
) {
    val resourceCount: Int get() = sections.sumOf(ContentSectionUi::resourceCount)
    val hasDownloads: Boolean get() = sections.any { section ->
        section.resources.any { resource -> resource.files.any(ContentFileUi::downloaded) }
    }
}

data class StarredFileUi(
    val sectionName: String,
    val resourceName: String,
    val resourceUrl: String?,
    val enabled: Boolean,
    val file: ContentFileUi,
)

data class StarredCourseUi(
    val id: Long,
    val name: String,
    val code: String?,
    val files: List<StarredFileUi>,
)

data class ContentsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val unsupported: Boolean = false,
    val courses: List<ContentCourseUi> = emptyList(),
    val progress: Map<String, MoodleFileDownloadProgress> = emptyMap(),
    val lastSyncEpoch: Long? = null,
    val message: String? = null,
    val fallbackUrl: String? = null,
) {
    val hasDownloads: Boolean get() = courses.any { course ->
        course.hasDownloads
    }
    val starredCourses: List<StarredCourseUi> get() = courses.mapNotNull { course ->
        val starredFiles = course.sections.flatMap { section ->
            section.resources.flatMap { resource ->
                resource.files.filter(ContentFileUi::starred).map { file ->
                    StarredFileUi(
                        sectionName = section.name,
                        resourceName = resource.name,
                        resourceUrl = resource.url,
                        enabled = resource.enabled,
                        file = file,
                    )
                }
            }
        }
        StarredCourseUi(course.id, course.name, course.code, starredFiles)
            .takeIf { it.files.isNotEmpty() }
    }
}

sealed interface ContentsEffect {
    data class OpenFile(val file: File, val mimeType: String?) : ContentsEffect
    data class OpenUrl(val url: String) : ContentsEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContentsViewModel(
    private val repository: MoodleContentRepository,
    private val moodle: MoodleSyncManager,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val account = MutableStateFlow(moodle.account())
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val fallbackUrl = MutableStateFlow<String?>(null)
    val effects = MutableSharedFlow<ContentsEffect>(extraBufferCapacity = 1)

    private val cached: Flow<MoodleCachedCatalog> = account.flatMapLatest { current ->
        current?.let(repository::catalog) ?: flowOf(EMPTY_CATALOG)
    }

    private val baseState = combine(account, cached, refreshing, message, fallbackUrl) { current, catalog, busy, text, fallback ->
        val courses = catalog.toUiCourses()
        ContentsUiState(
            connected = current != null,
            loading = current != null && busy && courses.isEmpty(),
            refreshing = busy,
            unsupported = catalog.sync?.contentsSupported == false,
            courses = courses,
            lastSyncEpoch = catalog.sync?.lastSuccessfulSyncEpoch,
            message = when (text) {
                DISMISSED_MESSAGE -> null
                null -> catalog.sync?.lastError
                else -> text
            },
            fallbackUrl = fallback,
        )
    }

    val state = combine(baseState, repository.downloadProgress) { current, progress ->
        current.copy(progress = progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContentsUiState())

    fun onVisible() {
        val current = moodle.account()
        account.value = current
        if (current == null || refreshing.value) return
        viewModelScope.launch {
            val lastSuccess = repository.syncState(current).first()?.lastSuccessfulSyncEpoch
            if (lastSuccess == null || now() - lastSuccess >= CONTENT_MAX_AGE_MILLIS) refresh()
        }
    }

    fun refresh() {
        if (refreshing.value) return
        val current = moodle.account()
        account.value = current
        if (current == null) return
        refreshing.value = true
        message.value = null
        fallbackUrl.value = null
        viewModelScope.launch {
            try {
                moodle.refreshContents()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: MoodleException) {
                if (error.kind == MoodleFailureKind.INVALID_TOKEN) {
                    val invalidated = try {
                        moodle.handleInvalidToken()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (invalidated) {
                        account.value = null
                        message.value = "La sesión de Aula Virtual venció. Vuelve a conectarla en Ajustes para ver tus contenidos guardados."
                    } else {
                        message.value = "Aula Virtual rechazó la solicitud temporalmente. Intenta de nuevo en unos minutos."
                    }
                } else {
                    repository.markRefreshFailure(current, error.contentMessage())
                    message.value = error.contentMessage()
                }
            } catch (_: Exception) {
                val text = "No se pudieron actualizar los contenidos de Aula Virtual"
                repository.markRefreshFailure(current, text)
                message.value = text
            } finally {
                refreshing.value = false
            }
        }
    }

    fun openResource(url: String?) {
        val safeUrl = url?.takeIf { it.startsWith("https://") } ?: return
        effects.tryEmit(ContentsEffect.OpenUrl(safeUrl))
    }

    fun openFile(fileId: String, mimeType: String?, resourceUrl: String?) {
        val current = moodle.account() ?: return
        if (repository.downloadProgress.value.containsKey(fileId)) return
        message.value = null
        fallbackUrl.value = null
        viewModelScope.launch {
            try {
                val file = repository.localFile(current, fileId) ?: repository.download(current, fileId)
                effects.emit(ContentsEffect.OpenFile(file, mimeType))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = if (resourceUrl == null) "No se pudo descargar el archivo."
                else "No se pudo descargar el archivo. Puedes abrir el recurso en Aula Virtual."
                fallbackUrl.value = resourceUrl
            }
        }
    }

    fun deleteDownload(fileId: String) {
        val current = moodle.account() ?: return
        viewModelScope.launch {
            repository.deleteDownload(current, fileId)
            message.value = "Descarga eliminada"
        }
    }

    fun setFileStarred(fileId: String, starred: Boolean) {
        val current = moodle.account() ?: return
        viewModelScope.launch {
            repository.setFileStarred(current, fileId, starred)
        }
    }

    fun deleteAllDownloads(courseId: Long? = null) {
        val current = moodle.account() ?: return
        viewModelScope.launch {
            repository.deleteAllDownloads(current, courseId)
            message.value = if (courseId == null) "Descargas de Aula Virtual eliminadas" else "Descargas del curso eliminadas"
        }
    }

    fun openFallback() {
        val url = fallbackUrl.value ?: return
        fallbackUrl.value = null
        effects.tryEmit(ContentsEffect.OpenUrl(url))
    }

    fun clearMessage() {
        message.value = DISMISSED_MESSAGE
        fallbackUrl.value = null
    }

    fun reportViewerUnavailable() {
        message.value = "No hay una aplicación compatible para abrir este contenido."
    }

    private fun MoodleCachedCatalog.toUiCourses(): List<ContentCourseUi> {
        val resourcesBySection = resources.groupBy { it.courseId to it.sectionId }
        val filesByResource = files.groupBy { it.resourceId }
        val sectionsByCourse = sections.groupBy { it.courseId }
        return courses.sortedBy { it.remoteOrder }.mapNotNull { course ->
            val contentSections = sectionsByCourse[course.courseId].orEmpty()
                .sortedBy { it.remoteOrder }
                .mapNotNull { section ->
                    val contentResources = resourcesBySection[course.courseId to section.sectionId].orEmpty()
                        .sortedBy { it.remoteOrder }
                        .mapNotNull { resource ->
                            val kind = runCatching { ResourceKind.valueOf(resource.moduleName) }.getOrDefault(ResourceKind.UNKNOWN)
                            val rawFiles = filesByResource[resource.resourceId].orEmpty()
                            val files = rawFiles.filterNot { file ->
                                isDecorativeImageFile(file.fileName, file.mimeType)
                            }
                            if (kind in setOf(ResourceKind.FILE, ResourceKind.FOLDER) && rawFiles.isNotEmpty() && files.isEmpty()) {
                                null
                            } else {
                                ContentResourceUi(
                                    id = resource.resourceId,
                                    name = resource.title,
                                    kind = kind,
                                    url = resource.url,
                                    enabled = resource.visible,
                                    availability = resource.availability,
                                    files = files.map { file ->
                                        ContentFileUi(
                                            id = file.fileId,
                                            name = file.fileName,
                                            mimeType = file.mimeType,
                                            sizeBytes = file.byteSize.takeIf { it >= 0 },
                                            downloaded = file.localFileName != null,
                                            downloadedBytes = file.downloadedBytes,
                                            starred = file.fileId in starredFileIds,
                                        )
                                    },
                                )
                            }
                        }
                    contentResources.takeIf(List<ContentResourceUi>::isNotEmpty)?.let {
                        ContentSectionUi(section.sectionId, section.title, it)
                    }
                }
            ContentCourseUi(course.courseId, course.title, course.shortName, contentSections)
                .takeIf { it.sections.isNotEmpty() }
        }
    }

    private fun MoodleException.contentMessage(): String = when (kind) {
        MoodleFailureKind.INVALID_TOKEN -> "La sesión de Aula Virtual venció. Vuelve a conectarla en Ajustes."
        MoodleFailureKind.NETWORK -> "Sin conexión. Se muestran los contenidos guardados."
        MoodleFailureKind.UNSUPPORTED -> "Aula Virtual no habilitó el acceso a contenidos para esta cuenta."
        else -> "No se pudieron actualizar los contenidos de Aula Virtual"
    }

    private companion object {
        const val CONTENT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1_000L
        const val DISMISSED_MESSAGE = "\u0000dismissed"
        val EMPTY_CATALOG = MoodleCachedCatalog(emptyList(), emptyList(), emptyList(), emptyList(), null, emptySet())
    }
}
