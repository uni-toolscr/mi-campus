package cr.micampus.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cr.micampus.app.core.designsystem.MiCampusTheme
import cr.micampus.app.core.designsystem.ShellTab
import cr.micampus.app.data.ai.MlKitNanoEngine
import cr.micampus.app.data.local.AppSettings
import cr.micampus.app.feature.calendar.CalendarPresentation
import cr.micampus.app.feature.chat.ChatScreen
import cr.micampus.app.feature.chat.ChatViewModel
import cr.micampus.app.feature.chat.AssetChatKnowledgeStore
import cr.micampus.app.feature.chat.LocalChatEventCommitter
import cr.micampus.app.feature.contents.ContentsEffect
import cr.micampus.app.feature.contents.ContentsScreen
import cr.micampus.app.feature.contents.ContentsViewModel
import cr.micampus.app.feature.calendar.CalendarScreen
import cr.micampus.app.feature.calendar.CalendarViewModel
import cr.micampus.app.feature.home.HomeScreen
import cr.micampus.app.feature.home.HomeViewModel
import cr.micampus.app.feature.importer.ImporterScreen
import cr.micampus.app.feature.importer.ImporterViewModel
import cr.micampus.app.feature.onboarding.OnboardingScreen
import cr.micampus.app.feature.onboarding.OnboardingViewModel
import cr.micampus.app.feature.settings.SettingsScreen
import cr.micampus.app.feature.settings.SettingsViewModel
import cr.micampus.app.feature.transport.TransportScreen
import cr.micampus.app.feature.transport.TransportViewModel
import cr.micampus.app.feature.update.UpdateAvailableDialog
import cr.micampus.app.feature.update.UpdateViewModel
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

private enum class Overlay { SETTINGS, CHAT }

@Composable
private fun MiCampusRoot(launchDestination: MutableStateFlow<String?>) {
    val app = LocalContext.current.applicationContext as MiCampusApplication
    val container = app.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val onboarding: OnboardingViewModel = viewModel(factory = factory { OnboardingViewModel(container.settings) })
    val onboardingState by onboarding.state.collectAsStateWithLifecycle()
    MiCampusTheme(themeMode = settings.themeMode) {
        if (!settings.onboardingDone) {
            OnboardingScreen(onboardingState, onboarding::setUcr, onboarding::setUna) { onboarding.complete {} }
        } else {
            MainDestinations(container, launchDestination)
        }
    }
}

@Composable
private fun MainDestinations(container: AppContainer, launchDestination: MutableStateFlow<String?>) {
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    val home: HomeViewModel = viewModel(factory = factory { HomeViewModel(container.events, container.settings, container.transport) })
    val calendar: CalendarViewModel = viewModel(factory = factory { CalendarViewModel(container.events, container.calendar, container.reminders, container.settings, container.widgets::refreshAll) })
    val transport: TransportViewModel = viewModel(factory = factory { TransportViewModel(container.transport, container.settings) })
    val contents: ContentsViewModel = viewModel(factory = factory {
        ContentsViewModel(container.moodleContents, container.moodle)
    })
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
    val update: UpdateViewModel = viewModel(factory = factory {
        UpdateViewModel(container.updateChecker, container.updateDownloader, container.settings, BuildConfig.VERSION_NAME)
    })
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
    val homeState by home.state.collectAsStateWithLifecycle()
    val calendarState by calendar.state.collectAsStateWithLifecycle()
    val transportState by transport.state.collectAsStateWithLifecycle()
    val contentsState by contents.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val importerState by importer.state.collectAsStateWithLifecycle()
    val chatState by chat.state.collectAsStateWithLifecycle()
    val updateState by update.state.collectAsStateWithLifecycle()

    val pendingLaunch by launchDestination.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(Unit) { update.checkForUpdate() }
    LaunchedEffect(updateState.installFile) {
        val file = updateState.installFile ?: return@LaunchedEffect
        if (!context.packageManager.canRequestPackageInstalls()) {
            installLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            update.consumeInstallFile()
            return@LaunchedEffect
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            // No installer available; nothing more we can do here.
        }
        update.consumeInstallFile()
    }
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
    LaunchedEffect(tab, overlay, settingsState.moodle.connected, settingsState.moodle.lastSyncEpoch) {
        if (tab == ShellTab.CONTENTS && overlay == null) contents.onVisible()
    }
    LaunchedEffect(pendingLaunch) {
        when (pendingLaunch) {
            WIDGET_DEST_CALENDAR_HORARIO -> {
                importing = false
                overlay = null
                tab = ShellTab.CALENDAR
                calendar.setPresentation(CalendarPresentation.HORARIO)
            }
            WIDGET_DEST_CALENDAR_AGENDA -> {
                importing = false
                overlay = null
                tab = ShellTab.CALENDAR
                calendar.setPresentation(CalendarPresentation.AGENDA)
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
        ImporterScreen(importerState, importer) { importing = false }
        return
    }

    BackHandler(enabled = overlay != null) { overlay = null }
    AppBackgroundSurface(Modifier.fillMaxSize()) {
        when (tab) {
            ShellTab.HOME -> HomeScreen(homeState, onImport = { importing = true }, onOpenSettings = { overlay = Overlay.SETTINGS })
            ShellTab.CALENDAR -> CalendarScreen(calendarState, calendar, expanded, onOpenSettings = { overlay = Overlay.SETTINGS })
            ShellTab.CONTENTS -> ContentsScreen(
                state = contentsState,
                onRefresh = contents::refresh,
                onOpenSettings = { overlay = Overlay.SETTINGS },
                onOpenResource = contents::openResource,
                onOpenFile = contents::openFile,
                onDeleteDownload = contents::deleteDownload,
                onSetFileStarred = contents::setFileStarred,
                onDeleteAllDownloads = contents::deleteAllDownloads,
                onOpenFallback = contents::openFallback,
                onClearMessage = contents::clearMessage,
            )
            ShellTab.TRANSPORT -> TransportScreen(
                transportState,
                transport::selectInstitution,
                transport::selectDirection,
                transport::selectDate,
                onOpenSettings = { overlay = Overlay.SETTINGS },
            )
        }
        FloatingNavToolbar(
            selected = tab,
            onSelect = { tab = it },
            onOpenChat = { overlay = Overlay.CHAT },
            aiEnabled = settingsState.settings.localAiEnabled || settingsState.settings.cloudAiEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp),
        )
        when (overlay) {
            Overlay.SETTINGS -> Surface(Modifier.fillMaxSize()) {
                SettingsScreen(settingsState, settings, update, onBack = { overlay = null })
            }
            Overlay.CHAT -> Surface(Modifier.fillMaxSize()) {
                ChatScreen(chatState, chat, onBack = { overlay = null })
            }
            null -> Unit
        }
        if (updateState.available != null && updateState.available?.version != updateState.dismissedVersion) {
            UpdateAvailableDialog(updateState, onDownload = update::startDownload, onDismiss = update::dismiss)
        }
    }
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
