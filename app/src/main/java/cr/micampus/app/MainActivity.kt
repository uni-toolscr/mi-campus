package cr.micampus.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cr.micampus.app.core.designsystem.AppBackgroundSurface
import cr.micampus.app.core.designsystem.FloatingNavToolbar
import cr.micampus.app.core.designsystem.LocalDockVisible
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.designsystem.ShellTab
import cr.micampus.app.core.designsystem.StatusBarScrim
import cr.micampus.app.core.designsystem.rememberDockScrollState
import cr.micampus.app.data.ai.MlKitNanoEngine
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.feature.calendar.CalendarPresentation
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.chat.AssetChatKnowledgeStore
import cr.micampus.app.feature.chat.ChatScreen
import cr.micampus.app.feature.chat.ChatViewModel
import cr.micampus.app.feature.chat.LocalChatEventCommitter
import cr.micampus.app.feature.contents.ContentsEffect
import cr.micampus.app.feature.contents.ContentsScreen
import cr.micampus.app.feature.contents.ContentsViewModel
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import cr.micampus.app.core.model.CampusEvent
import cr.micampus.app.core.model.EventKind
import cr.micampus.app.core.model.Institution
import cr.micampus.app.data.institution.directionKey
import cr.micampus.app.feature.detail.CourseSheet
import cr.micampus.app.feature.detail.DetailViewModel
import cr.micampus.app.feature.detail.EventDetailScreen
import cr.micampus.app.feature.home.HomeActions
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeViewModel
import cr.micampus.app.feature.importer.ImporterScreen
import cr.micampus.app.feature.importer.ImporterViewModel
import cr.micampus.app.feature.settings.SettingsPage
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportViewModel
import cr.micampus.app.feature.update.UpdateAvailableDialog
import cr.micampus.app.feature.update.UpdateViewModel
import cr.micampus.app.feature.update.rememberUpdateInstallAction
import cr.micampus.app.platform.widgets.EXTRA_WIDGET_DESTINATION
import cr.micampus.app.platform.widgets.WIDGET_DEST_CALENDAR_AGENDA
import cr.micampus.app.platform.widgets.WIDGET_DEST_CALENDAR_HORARIO
import cr.micampus.app.platform.widgets.WIDGET_DEST_TRANSPORT
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    // Deep-link target from a widget tap; null when launched normally. onNewIntent updates it so an
    // already-running (singleTop) instance also routes to the requested screen.
    private val launchDestination = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchDestination.value = intent?.getStringExtra(EXTRA_WIDGET_DESTINATION)
        // Strip the extra from the retained intent so a later config recreation (rotation,
        // theme change) doesn't re-read it in onCreate and re-route to an already-consumed target.
        intent?.removeExtra(EXTRA_WIDGET_DESTINATION)
        setContent { MiCampusRoot(launchDestination) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchDestination.value = intent.getStringExtra(EXTRA_WIDGET_DESTINATION)
        intent.removeExtra(EXTRA_WIDGET_DESTINATION)
    }
}

private enum class Overlay { SETTINGS, CHAT, EVENT }

@Composable
private fun MiCampusRoot(launchDestination: MutableStateFlow<String?>) {
    val app = LocalContext.current.applicationContext as MiCampusApplication
    val container = app.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    MiCampusTheme(themeMode = settings.themeMode) {
        MainDestinations(container, settings, launchDestination)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainDestinations(container: AppContainer, appSettings: AppSettings, launchDestination: MutableStateFlow<String?>) {
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    // A widget deep link into Calendar picks the presentation; CalendarRoute applies and clears it.
    var pendingCalendarPresentation by rememberSaveable { mutableStateOf<CalendarPresentation?>(null) }
    // Inicio's checklist asks Calendario to open its class editor once it's shown.
    var pendingClassEditor by rememberSaveable { mutableStateOf(false) }
    // Inicio's bus card opens Transporte on the same route ("UCR|<direction id>").
    var pendingTransportRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var detailEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseSheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    // ViewModels are created by the route that shows them, so startup only builds Home's.
    val update: UpdateViewModel = viewModel(factory = factory {
        UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
    })
    val updateState by update.state.collectAsStateWithLifecycle()
    val pendingLaunch by launchDestination.collectAsStateWithLifecycle()
    val attemptInstallUpdate = rememberUpdateInstallAction(update)
    val dock = rememberDockScrollState()
    val saveableTabs = rememberSaveableStateHolder()
    val motion = MaterialTheme.motionScheme
    val detail: DetailViewModel = viewModel(factory = factory {
        DetailViewModel(container.events, container.reminders, container.settings, container.widgets::refreshAll)
    })
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(detail) {
        detail.messages.collect { message ->
            val result = snackbar.showSnackbar(message.text, actionLabel = message.undo?.let { "Deshacer" }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
        }
    }
    val openEvent: (CampusEvent) -> Unit = { event ->
        if (event.kind == EventKind.CLASS) {
            courseSheetKey = event.title.trim()
        } else {
            courseSheetKey = null
            detailEventId = event.id
            overlay = Overlay.EVENT
        }
    }
    val openCourse: (String) -> Unit = { courseSheetKey = it }
    LaunchedEffect(Unit) { update.checkForUpdate() }
    LaunchedEffect(tab) { dock.visible = true }
    LaunchedEffect(pendingLaunch) {
        when (pendingLaunch) {
            WIDGET_DEST_CALENDAR_HORARIO -> {
                importing = false
                overlay = null
                tab = ShellTab.CALENDAR
                pendingCalendarPresentation = CalendarPresentation.HORARIO
            }
            WIDGET_DEST_CALENDAR_AGENDA -> {
                importing = false
                overlay = null
                tab = ShellTab.CALENDAR
                pendingCalendarPresentation = CalendarPresentation.AGENDA
            }
            WIDGET_DEST_TRANSPORT -> {
                importing = false
                overlay = null
                tab = ShellTab.TRANSPORT
            }
        }
        // Consume so ordinary navigation isn't overridden on later recompositions.
        if (pendingLaunch != null) launchDestination.value = null
    }

    if (importing) {
        ImporterRoute(container) { importing = false }
        return
    }

    // Overlays animate out after `overlay` clears, so keep rendering the last one during the exit.
    var shownOverlay by remember { mutableStateOf(overlay) }
    if (overlay != null) shownOverlay = overlay
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = overlay != null) { events ->
        try {
            events.collect { backProgress = it.progress }
            overlay = null
        } finally {
            backProgress = 0f
        }
    }
    AppBackgroundSurface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDockVisible provides dock.visible) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().nestedScroll(dock.connection),
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith
                        fadeOut(motion.fastEffectsSpec())
                },
                label = "tab",
            ) { current ->
                saveableTabs.SaveableStateProvider(current.name) {
                    val openSettings = { settingsPage = null; overlay = Overlay.SETTINGS }
                    val openAccounts = { settingsPage = SettingsPage.ACCOUNTS; overlay = Overlay.SETTINGS }
                    when (current) {
                        ShellTab.HOME -> HomeRoute(
                            container,
                            openSettings,
                            HomeActions(
                                onOpenEvent = openEvent,
                                onOpenCourse = openCourse,
                                onOpenBus = { bus ->
                                    pendingTransportRoute = bus?.let { directionKey(it.institution, it.directionId) }
                                    tab = ShellTab.TRANSPORT
                                },
                                onOpenAgenda = {
                                    pendingCalendarPresentation = CalendarPresentation.AGENDA
                                    tab = ShellTab.CALENDAR
                                },
                                onSetCompleted = { event, done -> detail.setCompleted(event, done) },
                                onImport = { importing = true },
                                onAddClasses = {
                                    pendingCalendarPresentation = CalendarPresentation.HORARIO
                                    pendingClassEditor = true
                                    tab = ShellTab.CALENDAR
                                },
                                onConnectAulaVirtual = openAccounts,
                            ),
                        )
                        ShellTab.CALENDAR -> CalendarRoute(
                            container,
                            expanded,
                            openSettings,
                            pendingCalendarPresentation,
                            onPresentationApplied = { pendingCalendarPresentation = null },
                            openClassEditor = pendingClassEditor,
                            onClassEditorOpened = { pendingClassEditor = false },
                            onOpenEvent = openEvent,
                            onOpenCourse = openCourse,
                            onImport = { importing = true },
                        )
                        ShellTab.CONTENTS -> ContentsRoute(container, overlayOpen = overlay != null, onOpenSettings = openAccounts)
                        ShellTab.TRANSPORT -> TransportRoute(container, openSettings, pendingTransportRoute) { pendingTransportRoute = null }
                    }
                }
            }
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
        FloatingNavToolbar(
            selected = tab,
            onSelect = { tab = it },
            onOpenChat = { overlay = Overlay.CHAT },
            aiEnabled = appSettings.localAiEnabled || appSettings.cloudAiEnabled,
            visible = dock.visible && overlay == null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp),
        )
        AnimatedVisibility(
            visible = overlay != null,
            enter = slideInHorizontally(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()),
            exit = slideOutHorizontally(motion.fastSpatialSpec()) { it / 3 } + fadeOut(motion.fastEffectsSpec()),
        ) {
            Surface(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Predictive back: shrink and nudge the overlay toward the edge as the gesture progresses.
                        val scale = 1f - 0.08f * backProgress
                        scaleX = scale
                        scaleY = scale
                        translationX = 48.dp.toPx() * backProgress
                        shape = RoundedCornerShape(32.dp * backProgress)
                        clip = backProgress > 0f
                    },
            ) {
                when (shownOverlay) {
                    Overlay.SETTINGS -> SettingsRoute(container, update, attemptInstallUpdate, settingsPage) { overlay = null }
                    Overlay.CHAT -> ChatRoute(container, onBack = { overlay = null }, onOpenFiles = { importing = true })
                    Overlay.EVENT -> detailEventId?.let { id ->
                        val detailState by detail.state.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        EventDetailScreen(
                            eventId = id,
                            state = detailState,
                            viewModel = detail,
                            onBack = { overlay = null },
                            onOpenCourse = openCourse,
                            onOpenSettings = { settingsPage = SettingsPage.REMINDERS; overlay = Overlay.SETTINGS },
                            onOpenUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                        )
                    }
                    null -> Unit
                }
            }
        }
        courseSheetKey?.let { key ->
            val detailState by detail.state.collectAsStateWithLifecycle()
            CourseSheet(key, detailState, detail, onDismiss = { courseSheetKey = null }, onOpenEvent = openEvent)
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = if (overlay == null) 88.dp else 16.dp),
        )
        if (updateState.available != null && updateState.available?.version != updateState.dismissedVersion) {
            UpdateAvailableDialog(updateState, onDownload = update::startDownload, onInstall = attemptInstallUpdate, onDismiss = update::dismiss)
        }
    }
}

@Composable
private fun HomeRoute(container: AppContainer, onOpenSettings: () -> Unit, actions: HomeActions) {
    val home: HomeViewModel = viewModel(factory = factory {
        HomeViewModel(container.events, container.settings, container.transport, aulaVirtualConnected = { container.moodle.account() != null })
    })
    val state by home.state.collectAsStateWithLifecycle()
    HomeScreen(state, onOpenSettings = onOpenSettings, actions = actions)
}

@Composable
private fun CalendarRoute(
    container: AppContainer,
    expanded: Boolean,
    onOpenSettings: () -> Unit,
    pendingPresentation: CalendarPresentation?,
    onPresentationApplied: () -> Unit,
    openClassEditor: Boolean,
    onClassEditorOpened: () -> Unit,
    onOpenEvent: (CampusEvent) -> Unit,
    onOpenCourse: (String) -> Unit,
    onImport: () -> Unit,
) {
    val calendar: CalendarViewModel = viewModel(factory = factory {
        CalendarViewModel(container.events, container.calendar, container.reminders, container.settings, container.widgets::refreshAll)
    })
    LaunchedEffect(pendingPresentation) {
        pendingPresentation?.let {
            calendar.setPresentation(it)
            onPresentationApplied()
        }
    }
    val state by calendar.state.collectAsStateWithLifecycle()
    CalendarScreen(
        state,
        calendar,
        expanded,
        onOpenSettings = onOpenSettings,
        onOpenEvent = onOpenEvent,
        onOpenCourse = onOpenCourse,
        onImport = onImport,
        openClassEditor = openClassEditor,
        onClassEditorOpened = onClassEditorOpened,
    )
}

@Composable
private fun ContentsRoute(container: AppContainer, overlayOpen: Boolean, onOpenSettings: () -> Unit) {
    val contents: ContentsViewModel = viewModel(factory = factory { ContentsViewModel(container.moodleContents, container.moodle) })
    val state by contents.state.collectAsStateWithLifecycle()
    // Read only inside the file rows, so byte-level progress doesn't recompose the whole screen.
    val downloadProgress = contents.downloadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(contents) {
        contents.effects.collect { effect ->
            try {
                when (effect) {
                    is ContentsEffect.OpenFile -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", effect.file)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, effect.mimeType ?: "*/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                    is ContentsEffect.OpenUrl -> context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()))
                }
            } catch (_: ActivityNotFoundException) {
                contents.reportViewerUnavailable()
            } catch (_: IllegalArgumentException) {
                contents.reportViewerUnavailable()
            }
        }
    }
    // Re-check when the tab appears and whenever Ajustes closes, since connecting Moodle happens there.
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen) contents.onVisible()
    }
    ContentsScreen(
        state = state,
        onRefresh = contents::refresh,
        onOpenSettings = onOpenSettings,
        onOpenResource = contents::openResource,
        onOpenFile = contents::openFile,
        onDeleteDownload = contents::deleteDownload,
        onSetFileStarred = contents::setFileStarred,
        onDeleteAllDownloads = contents::deleteAllDownloads,
        onOpenFallback = contents::openFallback,
        onClearMessage = contents::clearMessage,
        downloadProgress = { downloadProgress.value },
    )
}

@Composable
private fun TransportRoute(container: AppContainer, onOpenSettings: () -> Unit, pendingRoute: String?, onRouteApplied: () -> Unit) {
    val transport: TransportViewModel = viewModel(factory = factory { TransportViewModel(container.transport, container.settings) })
    LaunchedEffect(pendingRoute) {
        val (institution, directionId) = pendingRoute?.split('|', limit = 2) ?: return@LaunchedEffect
        runCatching { Institution.valueOf(institution) }.getOrNull()?.let { transport.select(it, directionId) }
        onRouteApplied()
    }
    val state by transport.state.collectAsStateWithLifecycle()
    TransportScreen(
        state,
        transport::selectInstitution,
        transport::selectDirection,
        transport::selectDate,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun SettingsRoute(container: AppContainer, update: UpdateViewModel, onInstallUpdate: () -> Unit, initialPage: SettingsPage?, onBack: () -> Unit) {
    val settings: SettingsViewModel = viewModel(factory = factory {
        SettingsViewModel(
            container.settings,
            container.keyStore,
            container.events,
            container.reminders,
            container.moodle,
            container.bannerProgress,
            container.widgets::refreshAll,
            diagnostics = container.diagnostics,
        )
    })
    val state by settings.state.collectAsStateWithLifecycle()
    SettingsScreen(state, settings, update, onInstallUpdate = onInstallUpdate, onBack = onBack, initialPage = initialPage)
}

@Composable
private fun ChatRoute(container: AppContainer, onBack: () -> Unit, onOpenFiles: () -> Unit) {
    val chat: ChatViewModel = viewModel(factory = factory {
        ChatViewModel(
            library = container.importedDocuments,
            transcriptions = container.transcriptions,
            settings = container.settings,
            keyStore = container.keyStore,
            cloud = container.cloud,
            knowledge = AssetChatKnowledgeStore(container.knowledge),
            eventCommitter = LocalChatEventCommitter(
                events = container.events,
                reminders = container.reminders,
                settings = container.settings,
                onDataChanged = container.widgets::refreshAll,
            ),
            nano = MlKitNanoEngine(diagnostics = container.diagnostics),
        )
    })
    val state by chat.state.collectAsStateWithLifecycle()
    ChatScreen(state, chat, onBack = onBack, onOpenFiles = onOpenFiles)
}

@Composable
private fun ImporterRoute(container: AppContainer, onClose: () -> Unit) {
    val importer: ImporterViewModel = viewModel(factory = factory {
        ImporterViewModel(
            container.documents,
            container.importedDocuments,
            container.events,
            container.settings,
            container.reminders,
            container.cloud,
            nano = MlKitNanoEngine(diagnostics = container.diagnostics),
            diagnostics = container.diagnostics,
            onDataChanged = container.widgets::refreshAll,
            transcriptions = container.transcriptions,
        )
    })
    val state by importer.state.collectAsStateWithLifecycle()
    ImporterScreen(state, importer, onClose)
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
