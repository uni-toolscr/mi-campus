package cr.micampus.app.feature.contents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.ResourceKind
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ContentsScreen(
    state: ContentsUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (fileId: String, mimeType: String?, resourceUrl: String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (fileId: String, starred: Boolean) -> Unit,
    onDeleteAllDownloads: (courseId: Long?) -> Unit,
    onOpenFallback: () -> Unit,
    onClearMessage: () -> Unit,
) {
    val expandedCourses = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedSections = remember { mutableStateMapOf<Long, Boolean>() }
    var starredExpanded by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<DownloadDeletion?>(null) }
    val showsCourseList = state.connected && !state.loading && state.courses.isNotEmpty()

    pendingDeletion?.let { deletion ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Eliminar descargas") },
            text = {
                Text(
                    deletion.courseName?.let { "Se eliminarán todos los archivos descargados de $it. Los contenidos seguirán apareciendo en la lista." }
                        ?: "Se eliminarán los archivos de Aula Virtual guardados en este dispositivo. Los contenidos seguirán apareciendo en la lista.",
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingDeletion = null; onDeleteAllDownloads(deletion.courseId) }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Cancelar") } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
            contentPadding = edgeToEdgeContentPadding(bottomExtra = FloatingToolbarClearance),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Contenidos", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("Recursos de Aula Virtual por curso", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = state.connected && !state.refreshing) {
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, contentDescription = "Actualizar contenidos")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Ajustes")
                }
            }
        }

        if ((showsCourseList && state.lastSyncEpoch != null) || (state.hasDownloads && state.progress.isEmpty())) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (showsCourseList) {
                        state.lastSyncEpoch?.let { lastSync ->
                            Text(
                                "Guardado sin conexión · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } ?: Spacer(Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (state.hasDownloads && state.progress.isEmpty()) {
                        TextButton(onClick = { pendingDeletion = DownloadDeletion(null, null) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text("Eliminar descargas")
                        }
                    }
                }
            }
        }

        if (state.message != null) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.message)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.fallbackUrl != null) {
                                TextButton(onClick = onOpenFallback) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                                    Text("Abrir en Aula Virtual")
                                }
                            }
                            TextButton(onClick = onClearMessage) { Text("Cerrar") }
                        }
                    }
                }
            }
        }

        when {
            !state.connected -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState("Conecta Aula Virtual", "Abre Ajustes e inicia sesión con tu cuenta de la UNA para ver los contenidos de tus cursos.")
                    Button(onClick = onOpenSettings) { Text("Ir a Ajustes") }
                }
            }
            state.loading -> item { LoadingState("Cargando contenidos") }
            state.unsupported && state.courses.isEmpty() -> item {
                EmptyState(
                    "Contenidos no disponibles",
                    "Aula Virtual no habilitó el servicio de contenidos para esta cuenta. La sincronización de fechas y entregas seguirá funcionando.",
                    offline = true,
                )
            }
            state.courses.isEmpty() -> item {
                EmptyState("No hay contenidos publicados", "Cuando tus cursos publiquen archivos, carpetas, páginas o enlaces aparecerán aquí.")
            }
            else -> {
                if (state.unsupported) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text(
                                "Se muestran contenidos guardados. Aula Virtual ya no permite actualizarlos mediante la aplicación.",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
                item(key = "starred-folder") {
                    StarredFolderCard(
                        modifier = Modifier.animateItem(),
                        courses = state.starredCourses,
                        expanded = starredExpanded,
                        onToggle = { starredExpanded = !starredExpanded },
                        progress = state.progress,
                        onOpenFile = onOpenFile,
                        onDeleteDownload = onDeleteDownload,
                        onSetFileStarred = onSetFileStarred,
                    )
                }
                items(state.courses, key = ContentCourseUi::id) { course ->
                    val expanded = expandedCourses[course.id] ?: (state.courses.size == 1)
                    CourseContentCard(
                        modifier = Modifier.animateItem(),
                        course = course,
                        expanded = expanded,
                        onToggle = { expandedCourses[course.id] = !expanded },
                        expandedSections = expandedSections,
                        progress = state.progress,
                        onOpenResource = onOpenResource,
                        onOpenFile = onOpenFile,
                        onDeleteDownload = onDeleteDownload,
                        onSetFileStarred = onSetFileStarred,
                        onDeleteCourseDownloads = {
                            pendingDeletion = DownloadDeletion(course.id, course.name)
                        },
                    )
                }
            }
        }
        }
        Spacer(
            Modifier
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, Color.Transparent)))
                .align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun StarredFolderCard(
    modifier: Modifier = Modifier,
    courses: List<StarredCourseUi>,
    expanded: Boolean,
    onToggle: () -> Unit,
    progress: Map<String, cr.micampus.app.data.moodle.MoodleFileDownloadProgress>,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val fileCount = courses.sumOf { it.files.size }
    val rotation by folderChevronRotation(expanded, "starred-chevron")
    Card(
        onClick = onToggle,
        modifier = modifier.testTag("starred-folder"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Destacados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (fileCount == 1) "1 archivo" else "$fileCount archivos",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Contraer Destacados" else "Expandir Destacados",
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = folderEnterTransition(),
                exit = folderExitTransition(),
            ) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    if (courses.isEmpty()) {
                        Text(
                            "Marca la estrella de un archivo para encontrarlo rápidamente aquí.",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        courses.forEach { course ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    listOfNotNull(course.name, course.code).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                course.files.forEach { starred ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                    ) {
                                        ContentFileRow(
                                            file = starred.file,
                                            enabled = starred.enabled,
                                            resourceUrl = starred.resourceUrl,
                                            progress = progress,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            sourceLabel = "${starred.sectionName} · ${starred.resourceName}",
                                            onOpenFile = onOpenFile,
                                            onDeleteDownload = onDeleteDownload,
                                            onSetFileStarred = onSetFileStarred,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseContentCard(
    modifier: Modifier = Modifier,
    course: ContentCourseUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedSections: MutableMap<Long, Boolean>,
    progress: Map<String, cr.micampus.app.data.moodle.MoodleFileDownloadProgress>,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
    onDeleteCourseDownloads: () -> Unit,
) {
    val rotation by folderChevronRotation(expanded, "course-chevron")
    Card(
        onClick = onToggle,
        modifier = modifier.testTag("course-folder-${course.id}"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        course.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(course.code, "${course.resourceCount} recursos").joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Contraer curso" else "Expandir curso",
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = folderEnterTransition(),
                exit = folderExitTransition(),
            ) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    if (course.hasDownloads) {
                        TextButton(
                            onClick = onDeleteCourseDownloads,
                            enabled = progress.isEmpty(),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Text("Eliminar descargas del curso")
                        }
                    }
                    course.sections.forEach { section ->
                        val sectionExpanded = expandedSections[section.id] == true
                        SectionContents(
                            section = section,
                            expanded = sectionExpanded,
                            onToggle = { expandedSections[section.id] = !sectionExpanded },
                            progress = progress,
                            onOpenResource = onOpenResource,
                            onOpenFile = onOpenFile,
                            onDeleteDownload = onDeleteDownload,
                            onSetFileStarred = onSetFileStarred,
                        )
                    }
                }
            }
        }
    }
}

private data class DownloadDeletion(val courseId: Long?, val courseName: String?)

@Composable
private fun SectionContents(
    section: ContentSectionUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    progress: Map<String, cr.micampus.app.data.moodle.MoodleFileDownloadProgress>,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val rotation by folderChevronRotation(expanded, "section-chevron")
    Column(Modifier.testTag("content-section-${section.id}")) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(section.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${section.resourceCount} recursos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Contraer sección" else "Expandir sección",
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = folderEnterTransition(),
            exit = folderExitTransition(),
        ) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                section.resources.forEachIndexed { index, resource ->
                    ResourceRow(
                        resource = resource,
                        resourceIndex = index,
                        resourceCount = section.resources.size,
                        progress = progress,
                        onOpenResource = onOpenResource,
                        onOpenFile = onOpenFile,
                        onDeleteDownload = onDeleteDownload,
                        onSetFileStarred = onSetFileStarred,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(
    resource: ContentResourceUi,
    resourceIndex: Int,
    resourceCount: Int,
    progress: Map<String, cr.micampus.app.data.moodle.MoodleFileDownloadProgress>,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val actionable = resource.enabled && resource.url != null && resource.kind !in setOf(ResourceKind.FILE, ResourceKind.FOLDER, ResourceKind.LABEL)
    val shape = when {
        resourceCount == 1 -> RoundedCornerShape(16.dp)
        resourceIndex == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        resourceIndex == resourceCount - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(4.dp)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(enabled = actionable) { onOpenResource(resource.url) },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (resource.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (resource.enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(resourceIcon(resource.kind), contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(resource.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(resourceLabel(resource.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    resource.availability?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
                if (actionable) Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Abrir en Aula Virtual", modifier = Modifier.size(20.dp))
            }
            resource.files.forEach { file ->
                ContentFileRow(
                    file = file,
                    enabled = resource.enabled,
                    resourceUrl = resource.url,
                    progress = progress,
                    modifier = Modifier.padding(start = 50.dp),
                    onOpenFile = onOpenFile,
                    onDeleteDownload = onDeleteDownload,
                    onSetFileStarred = onSetFileStarred,
                )
            }
        }
    }
}

@Composable
private fun ContentFileRow(
    file: ContentFileUi,
    enabled: Boolean,
    resourceUrl: String?,
    progress: Map<String, cr.micampus.app.data.moodle.MoodleFileDownloadProgress>,
    modifier: Modifier = Modifier,
    sourceLabel: String? = null,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val active = progress[file.id]
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && active == null) { onOpenFile(file.id, file.mimeType, resourceUrl) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (file.downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sourceLabel?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (file.downloaded && active == null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text("Sin conexión · ${formatBytes(file.downloadedBytes)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(
                    when {
                        active != null -> downloadProgressLabel(active.bytesDownloaded, active.totalBytes)
                        file.sizeBytes != null -> formatBytes(file.sizeBytes)
                        else -> "Descargar archivo"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active != null) {
                val total = active.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (active.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        Column {
            IconButton(
                onClick = { onSetFileStarred(file.id, !file.starred) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (file.starred) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = if (file.starred) "Quitar ${file.name} de Destacados" else "Agregar ${file.name} a Destacados",
                    tint = if (file.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.downloaded) {
                IconButton(onClick = { onDeleteDownload(file.id) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Eliminar descarga ${file.name}")
                }
            }
        }
    }
}

private fun resourceIcon(kind: ResourceKind): ImageVector = when (kind) {
    ResourceKind.FILE -> Icons.Outlined.Description
    ResourceKind.FOLDER -> Icons.Outlined.Folder
    ResourceKind.PAGE -> Icons.Outlined.Description
    ResourceKind.URL -> Icons.Outlined.Link
    ResourceKind.FORUM -> Icons.Outlined.Forum
    ResourceKind.SCORM -> Icons.Outlined.Extension
    ResourceKind.LABEL -> Icons.AutoMirrored.Outlined.Label
    ResourceKind.UNKNOWN -> Icons.Outlined.Extension
}

private fun resourceLabel(kind: ResourceKind): String = when (kind) {
    ResourceKind.FILE -> "Archivo"
    ResourceKind.FOLDER -> "Carpeta"
    ResourceKind.PAGE -> "Página"
    ResourceKind.URL -> "Enlace"
    ResourceKind.FORUM -> "Foro"
    ResourceKind.SCORM -> "Paquete SCORM"
    ResourceKind.LABEL -> "Información"
    ResourceKind.UNKNOWN -> "Actividad"
}

@Composable
private fun folderChevronRotation(expanded: Boolean, label: String) = animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = tween(
        durationMillis = if (expanded) FOLDER_EXPAND_MILLIS else FOLDER_COLLAPSE_MILLIS,
        easing = FastOutSlowInEasing,
    ),
    label = label,
)

private fun folderEnterTransition(): EnterTransition = expandVertically(
    animationSpec = tween(FOLDER_EXPAND_MILLIS, easing = FastOutSlowInEasing),
    expandFrom = Alignment.Top,
) + fadeIn(animationSpec = tween(150, delayMillis = 50))

private fun folderExitTransition(): ExitTransition = shrinkVertically(
    animationSpec = tween(FOLDER_COLLAPSE_MILLIS, easing = FastOutSlowInEasing),
    shrinkTowards = Alignment.Top,
) + fadeOut(animationSpec = tween(120))

private const val FOLDER_EXPAND_MILLIS = 250
private const val FOLDER_COLLAPSE_MILLIS = 200

private fun downloadProgressLabel(downloaded: Long, total: Long?): String =
    if (total == null || total <= 0) "Descargando · ${formatBytes(downloaded)}"
    else "Descargando · ${(downloaded * 100 / total).coerceIn(0, 100)}%"

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1_024.0 * 1_024.0))
}
