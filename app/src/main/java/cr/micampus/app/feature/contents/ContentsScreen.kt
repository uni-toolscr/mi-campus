package cr.micampus.app.feature.contents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cr.micampus.app.core.designsystem.EmptyState
import cr.micampus.app.core.designsystem.FloatingToolbarClearance
import cr.micampus.app.core.designsystem.LoadingState
import cr.micampus.app.core.designsystem.ScreenHeader
import cr.micampus.app.core.designsystem.SettingsAction
import cr.micampus.app.core.designsystem.assignCourseColors
import cr.micampus.app.core.designsystem.coursePalette
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.ResourceKind
import cr.micampus.app.data.moodle.MoodleFileDownloadProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private typealias DownloadProgress = () -> Map<String, MoodleFileDownloadProgress>

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    downloadProgress: DownloadProgress = { emptyMap() },
) {
    val expandedCourses = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedSections = remember { mutableStateMapOf<Long, Boolean>() }
    var starredExpanded by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<DownloadDeletion?>(null) }
    val downloadsIdle = remember(downloadProgress) { derivedStateOf { downloadProgress().isEmpty() } }
    val palette = coursePalette()
    val courseColors = remember(state.courses, palette.size) {
        assignCourseColors(state.courses.map(ContentCourseUi::name), emptyMap(), palette.size)
    }
    val pullState = rememberPullToRefreshState()
    val pullRefreshing = state.refreshing && !state.loading

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

    PullToRefreshBox(
        isRefreshing = pullRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        enabled = state.connected,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = pullRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
            contentPadding = edgeToEdgeContentPadding(bottomExtra = FloatingToolbarClearance),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header", contentType = "header") {
                ContentsHeader(
                    state = state,
                    downloadsIdle = downloadsIdle::value,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    onDeleteAllDownloads = { pendingDeletion = DownloadDeletion(null, null) },
                )
            }

            if (state.message != null) {
                item(key = "message", contentType = "message") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.animateItem(),
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
                !state.connected -> item(key = "disconnected", contentType = "state") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            "Conecta Aula Virtual",
                            "Abre Ajustes e inicia sesión con tu cuenta de la UNA para ver los contenidos de tus cursos.",
                            icon = Icons.Outlined.School,
                            shape = MaterialShapes.Clover4Leaf,
                        )
                        Button(onClick = onOpenSettings) { Text("Ir a Ajustes") }
                    }
                }
                state.loading -> item(key = "loading", contentType = "state") { LoadingState("Cargando contenidos") }
                state.unsupported && state.courses.isEmpty() -> item(key = "unsupported", contentType = "state") {
                    EmptyState(
                        "Contenidos no disponibles",
                        "Aula Virtual no habilitó el servicio de contenidos para esta cuenta. La sincronización de fechas y entregas seguirá funcionando.",
                        offline = true,
                    )
                }
                state.courses.isEmpty() -> item(key = "empty", contentType = "state") {
                    EmptyState(
                        "No hay contenidos publicados",
                        "Cuando tus cursos publiquen archivos, carpetas, páginas o enlaces aparecerán aquí.",
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
                else -> {
                    if (state.unsupported) {
                        item(key = "unsupported-banner", contentType = "banner") {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Text(
                                    "Se muestran contenidos guardados. Aula Virtual ya no permite actualizarlos mediante la aplicación.",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                    }
                    item(key = "starred-folder", contentType = "starred") {
                        StarredFolderCard(
                            modifier = Modifier.animateItem(),
                            courses = state.starredCourses,
                            expanded = starredExpanded,
                            onToggle = { starredExpanded = !starredExpanded },
                            downloadProgress = downloadProgress,
                            onOpenFile = onOpenFile,
                            onDeleteDownload = onDeleteDownload,
                            onSetFileStarred = onSetFileStarred,
                        )
                    }
                    items(state.courses, key = ContentCourseUi::id, contentType = { "course" }) { course ->
                        val expanded = expandedCourses[course.id] ?: (state.courses.size == 1)
                        CourseContentCard(
                            modifier = Modifier.animateItem(),
                            course = course,
                            colors = palette[courseColors[course.name] ?: 0],
                            expanded = expanded,
                            onToggle = { expandedCourses[course.id] = !expanded },
                            expandedSections = expandedSections,
                            downloadsIdle = downloadsIdle::value,
                            downloadProgress = downloadProgress,
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
private fun ContentsHeader(
    state: ContentsUiState,
    downloadsIdle: () -> Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteAllDownloads: () -> Unit,
) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val lastSync = state.lastSyncEpoch
    ScreenHeader(
        "Contenidos",
        subtitle = if (state.connected && lastSync != null) lastSyncLabel(lastSync, now)
        else "Recursos de Aula Virtual por curso",
    ) {
        if (state.connected) {
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Actualizar contenidos")
            }
        }
        if (state.hasDownloads) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Más opciones")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Eliminar descargas") },
                        onClick = { menuOpen = false; onDeleteAllDownloads() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        enabled = downloadsIdle(),
                    )
                }
            }
        }
        SettingsAction(onOpenSettings)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StarredFolderCard(
    modifier: Modifier = Modifier,
    courses: List<StarredCourseUi>,
    expanded: Boolean,
    onToggle: () -> Unit,
    downloadProgress: DownloadProgress,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val fileCount = courses.sumOf { it.files.size }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("starred-folder"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column {
            FolderHeader(
                title = "Destacados",
                subtitle = if (fileCount == 1) "1 archivo" else "$fileCount archivos",
                expanded = expanded,
                toggleLabel = if (expanded) "Contraer Destacados" else "Expandir Destacados",
                onToggle = onToggle,
            ) {
                Surface(
                    shape = MaterialShapes.Sunny.toShape(),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            AnimatedVisibility(visible = expanded, enter = folderEnterTransition(), exit = folderExitTransition()) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (courses.isEmpty()) {
                        Text(
                            "Marca la estrella de un archivo para encontrarlo rápidamente aquí.",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    } else {
                        courses.forEach { course ->
                            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                                Text(
                                    listOfNotNull(course.name, course.code).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                )
                                course.files.forEachIndexed { index, starred ->
                                    ContentFileItem(
                                        file = starred.file,
                                        enabled = starred.enabled,
                                        resourceUrl = starred.resourceUrl,
                                        index = index,
                                        count = course.files.size,
                                        downloadProgress = downloadProgress,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CourseContentCard(
    modifier: Modifier = Modifier,
    course: ContentCourseUi,
    colors: Pair<Color, Color>,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedSections: MutableMap<Long, Boolean>,
    downloadsIdle: () -> Boolean,
    downloadProgress: DownloadProgress,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
    onDeleteCourseDownloads: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("course-folder-${course.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            FolderHeader(
                title = course.name,
                subtitle = listOfNotNull(course.code, "${course.resourceCount} recursos").joinToString(" · "),
                expanded = expanded,
                toggleLabel = if (expanded) "Contraer curso" else "Expandir curso",
                onToggle = onToggle,
            ) {
                Surface(
                    shape = MaterialShapes.Cookie4Sided.toShape(),
                    color = colors.first,
                    contentColor = colors.second,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            course.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                    }
                }
            }
            AnimatedVisibility(visible = expanded, enter = folderEnterTransition(), exit = folderExitTransition()) {
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (course.hasDownloads) {
                        TextButton(
                            onClick = onDeleteCourseDownloads,
                            enabled = downloadsIdle(),
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
                            downloadProgress = downloadProgress,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    toggleLabel: String,
    onToggle: () -> Unit,
    badge: @Composable () -> Unit,
) {
    val rotation by folderChevronRotation(expanded)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        badge()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ExpandMore, contentDescription = toggleLabel, modifier = Modifier.rotate(rotation))
    }
}

private data class DownloadDeletion(val courseId: Long?, val courseName: String?)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionContents(
    section: ContentSectionUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    downloadProgress: DownloadProgress,
    onOpenResource: (String?) -> Unit,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    val rotation by folderChevronRotation(expanded)
    val itemCount = section.resources.sumOf { 1 + it.files.size }
    Column(Modifier.testTag("content-section-${section.id}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(section.name, style = MaterialTheme.typography.titleSmallEmphasized)
                Text("${section.resourceCount} recursos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Contraer sección" else "Expandir sección",
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(visible = expanded, enter = folderEnterTransition(), exit = folderExitTransition()) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                var index = 0
                section.resources.forEach { resource ->
                    ResourceItem(resource, index++, itemCount, onOpenResource)
                    resource.files.forEach { file ->
                        ContentFileItem(
                            file = file,
                            enabled = resource.enabled,
                            resourceUrl = resource.url,
                            index = index++,
                            count = itemCount,
                            downloadProgress = downloadProgress,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResourceItem(
    resource: ContentResourceUi,
    index: Int,
    count: Int,
    onOpenResource: (String?) -> Unit,
) {
    val actionable = resource.enabled && resource.url != null && resource.kind !in setOf(ResourceKind.FILE, ResourceKind.FOLDER, ResourceKind.LABEL)
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val colors = groupedListColors()
    val leading: @Composable () -> Unit = { ItemIcon(resourceIcon(resource.kind)) }
    val supporting: @Composable () -> Unit = {
        Column {
            Text(resourceLabel(resource.kind))
            resource.availability?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
    val headline: @Composable () -> Unit = { Text(resource.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    if (actionable) {
        SegmentedListItem(
            onClick = { onOpenResource(resource.url) },
            shapes = shapes,
            leadingContent = leading,
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Abrir en Aula Virtual", modifier = Modifier.size(20.dp)) },
            supportingContent = supporting,
            colors = colors,
            content = headline,
        )
    } else {
        SegmentedListItem(
            shapes = shapes,
            enabled = resource.enabled,
            leadingContent = leading,
            supportingContent = supporting,
            colors = colors,
            content = headline,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentFileItem(
    file: ContentFileUi,
    enabled: Boolean,
    resourceUrl: String?,
    index: Int,
    count: Int,
    downloadProgress: DownloadProgress,
    sourceLabel: String? = null,
    onOpenFile: (String, String?, String?) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onSetFileStarred: (String, Boolean) -> Unit,
) {
    // Only this row observes its own entry, so progress ticks never recompose the rest of the list.
    val active by remember(file.id, downloadProgress) { derivedStateOf { downloadProgress()[file.id] } }
    SegmentedListItem(
        onClick = { if (active == null) onOpenFile(file.id, file.mimeType, resourceUrl) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        leadingContent = { ItemIcon(if (file.downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download) },
        trailingContent = {
            Row {
                IconButton(onClick = { onSetFileStarred(file.id, !file.starred) }, modifier = Modifier.size(48.dp)) {
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
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sourceLabel?.let { Text(it) }
                val current = active
                when {
                    current != null -> {
                        Text(downloadProgressLabel(current.bytesDownloaded, current.totalBytes))
                        val fraction = downloadFraction(current.bytesDownloaded, current.totalBytes)
                        if (fraction != null) {
                            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        } else {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                    }
                    file.downloaded -> Surface(
                        shape = MaterialTheme.shapes.small,
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
                    else -> Text(file.sizeBytes?.let(::formatBytes) ?: "Descargar archivo")
                }
            }
        },
        colors = groupedListColors(),
    ) {
        Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ItemIcon(icon: ImageVector) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
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
private fun folderChevronRotation(expanded: Boolean) = animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    label = "folder-chevron",
)

@Composable
private fun folderEnterTransition(): EnterTransition = expandVertically(
    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    expandFrom = Alignment.Top,
) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

// Collapsing uses the critically damped effects spring: a bouncy spatial spring would overshoot
// below zero height and leave a visible snap when the content is finally removed.
@Composable
private fun folderExitTransition(): ExitTransition = shrinkVertically(
    animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    shrinkTowards = Alignment.Top,
) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

private val SyncLocale = Locale.forLanguageTag("es-CR")
private val SyncDateThisYear = DateTimeFormatter.ofPattern("d 'de' MMMM", SyncLocale)
private val SyncDateOtherYear = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SyncLocale)

internal fun lastSyncLabel(lastSyncEpoch: Long, nowEpoch: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (nowEpoch - lastSyncEpoch).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "Sincronizado ahora"
        minutes < 60 -> "Sincronizado hace $minutes min"
        minutes < 24 * 60 -> "Sincronizado hace ${minutes / 60} h"
        else -> {
            val synced = Instant.ofEpochMilli(lastSyncEpoch).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowEpoch).atZone(zone).toLocalDate()
            when {
                synced == today.minusDays(1) -> "Sincronizado ayer"
                synced.year == today.year -> "Sincronizado el ${SyncDateThisYear.format(synced)}"
                else -> "Sincronizado el ${SyncDateOtherYear.format(synced)}"
            }
        }
    }
}

/** Determinate fraction when the server reported a size; null means the indicator must be indeterminate. */
internal fun downloadFraction(downloaded: Long, total: Long?): Float? =
    total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }

internal fun downloadProgressLabel(downloaded: Long, total: Long?): String =
    if (total == null || total <= 0) "Descargando · ${formatBytes(downloaded)}"
    else "Descargando · ${(downloaded * 100 / total).coerceIn(0, 100)}%"

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1_024.0 * 1_024.0))
}
