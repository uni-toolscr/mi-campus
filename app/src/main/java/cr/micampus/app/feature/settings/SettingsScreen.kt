package cr.micampus.app.feature.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.micampus.app.BuildConfig
import cr.micampus.app.core.designsystem.groupedListColors
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.core.model.UnfinishedCoursePolicy
import cr.micampus.app.data.ai.NanoCapability
import cr.micampus.app.data.update.UpdateDownloadState
import cr.micampus.app.feature.update.UpdateViewModel
import cr.micampus.app.platform.reminders.ReminderScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    update: UpdateViewModel,
    onInstallUpdate: () -> Unit,
    onBack: () -> Unit = {},
    initialPage: SettingsPage? = null,
) {
    val context = LocalContext.current
    val updateUiState by update.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationAvailability by remember(context) { mutableStateOf(notificationAvailability(context)) }
    val remindersEnabled by rememberUpdatedState(state.settings.remindersEnabled)
    val exactRemindersEnabled by rememberUpdatedState(state.settings.exactReminders)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previousAvailability = notificationAvailability
                val currentAvailability = notificationAvailability(context)
                notificationAvailability = currentAvailability
                if (
                    previousAvailability != NotificationAvailability.AVAILABLE &&
                    currentAvailability == NotificationAvailability.AVAILABLE &&
                    remindersEnabled
                ) {
                    viewModel.setReminders(true)
                }
                if (
                    Build.VERSION.SDK_INT >= 31 &&
                    exactRemindersEnabled &&
                    !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                ) {
                    viewModel.setExactReminders(false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel) { viewModel.refreshLocalAiCapability() }
    LaunchedEffect(state.diagnosticsExport) {
        val export = state.diagnosticsExport ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, export.uri)
            putExtra(Intent.EXTRA_TITLE, export.fileName)
            clipData = ClipData.newUri(context.contentResolver, export.fileName, export.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Compartir diagnóstico")) }
        viewModel.consumeDiagnosticsExport()
    }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showMoodleDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationAvailability = notificationAvailability(context)
        viewModel.setReminders(granted)
    }
    val exactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        viewModel.setExactReminders(granted)
    }
    val openNotificationSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    if (showKeyDialog) KeyDialog(onDismiss = { showKeyDialog = false }, onSave = { viewModel.saveKey(it); showKeyDialog = false })
    if (showMoodleDialog) MoodleLoginDialog(
        onDismiss = { showMoodleDialog = false },
        onConnect = { username, password ->
            viewModel.connectMoodle(username, password)
            showMoodleDialog = false
        },
    )
    if (showProgressDialog) MoodleLoginDialog(
        title = "Actualizar progreso académico",
        confirmLabel = "Actualizar",
        onDismiss = { showProgressDialog = false },
        onConnect = { username, password -> viewModel.refreshAcademicProgress(username, password); showProgressDialog = false },
    )

    // Keyed on initialPage so a caller asking for a different page while Ajustes is open still lands there.
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    // Debug-only page: a stale or explicit request for it falls back to the front page in release builds.
    val shownPage = page.takeUnless { it == SettingsPage.ADVANCED && !state.diagnosticsAvailable }
    BackHandler(enabled = shownPage != null) { page = null }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = shownPage,
        transitionSpec = {
            // Shared X axis: sub-pages enter from the end, the front page returns from the start.
            val direction = if (targetState != null) 1 else -1
            (slideInHorizontally(motion.defaultSpatialSpec()) { direction * it / 5 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                (slideOutHorizontally(motion.fastSpatialSpec()) { -direction * it / 5 } + fadeOut(motion.fastEffectsSpec()))
        },
        label = "settings-page",
    ) { current ->
        SettingsPageScaffold(
            title = current?.title ?: "Ajustes",
            onBack = if (current == null) onBack else { { page = null } },
        ) {
            when (current) {
                null -> {
                    if (notificationAvailability != NotificationAvailability.AVAILABLE) item {
                        NotificationsBlockedBanner(onEnable = openNotificationSettings)
                    }
                    item {
                        SettingsSection("Mi semestre") {
                            row { PageRow(it, Icons.Outlined.AccountBalance, SettingsPage.INSTITUTIONS, institutionsSummary(state.settings.ucrEnabled, state.settings.unaEnabled)) { page = it } }
                            row { PageRow(it, Icons.Outlined.SyncAlt, SettingsPage.ACCOUNTS, accountsSummary(state.moodle)) { page = it } }
                            row {
                                PageRow(
                                    it,
                                    Icons.Outlined.Notifications,
                                    SettingsPage.REMINDERS,
                                    remindersSummary(
                                        enabled = state.settings.remindersEnabled,
                                        exact = state.settings.exactReminders,
                                        blocked = notificationAvailability != NotificationAvailability.AVAILABLE,
                                    ),
                                ) { page = it }
                            }
                        }
                    }
                    item {
                        SettingsSection("App") {
                            row { PageRow(it, Icons.Outlined.Palette, SettingsPage.APPEARANCE, appearanceSummary(state.settings.themeMode, state.settings.use12hClock)) { page = it } }
                            row { PageRow(it, Icons.Outlined.AutoAwesome, SettingsPage.AI, aiSummary(state.settings.localAiEnabled, state.settings.cloudAiEnabled, state.keyPresent)) { page = it } }
                        }
                    }
                    item {
                        SettingsSection("Soporte") {
                            if (state.diagnosticsAvailable) row {
                                PageRow(it, Icons.Outlined.Build, SettingsPage.ADVANCED, "Diagnóstico de importación, registro técnico") { page = it }
                            }
                            row {
                                PageRow(
                                    it,
                                    Icons.Outlined.Info,
                                    SettingsPage.ABOUT,
                                    aboutSummary(
                                        version = BuildConfig.VERSION_NAME,
                                        checking = updateUiState.checking,
                                        downloaded = updateUiState.download is UpdateDownloadState.Completed,
                                        availableVersion = updateUiState.available?.version,
                                    ),
                                ) { page = it }
                            }
                        }
                    }
                }
                SettingsPage.INSTITUTIONS -> item {
                    SettingsSection("Instituciones") {
                        row { SettingSwitchRow(it, "Universidad de Costa Rica", state.settings.ucrEnabled) { enabled -> viewModel.setInstitutions(enabled, state.settings.unaEnabled) } }
                        row { SettingSwitchRow(it, "Universidad Nacional", state.settings.unaEnabled) { enabled -> viewModel.setInstitutions(state.settings.ucrEnabled, enabled) } }
                    }
                }
                SettingsPage.APPEARANCE -> item {
                    SettingsSection("Tema y hora") {
                        row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                shapes = shapes,
                                supportingContent = {
                                    ThemeModeButtonGroup(state.settings.themeMode, viewModel::setTheme, Modifier.padding(top = 8.dp))
                                },
                            ) { Text("Tema") }
                        }
                        row { SettingSwitchRow(it, "Formato de 12 horas (a. m./p. m.)", state.settings.use12hClock) { enabled -> viewModel.setUse12hClock(enabled) } }
                    }
                }
                SettingsPage.REMINDERS -> item {
                    SettingsSection("Notificaciones", caption = "Sin acceso exacto, Mi Campus usa WorkManager y la entrega puede retrasarse.") {
                        row { shapes ->
                            SettingSwitchRow(shapes, "Notificaciones de eventos", state.settings.remindersEnabled) { enabled ->
                                if (!enabled) viewModel.setReminders(false)
                                else if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else viewModel.setReminders(true)
                            }
                        }
                        if (notificationAvailability != NotificationAvailability.AVAILABLE) row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                onClick = openNotificationSettings,
                                shapes = shapes,
                                leadingContent = { Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                                supportingContent = { Text(notificationAvailability.explanation) },
                            ) { Text("Abrir ajustes de notificaciones") }
                        }
                        row { shapes ->
                            SettingSwitchRow(shapes, "Entrega exacta", state.settings.exactReminders, enabled = state.settings.remindersEnabled) { enabled ->
                                if (!enabled) viewModel.setExactReminders(false)
                                else if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                                    exactLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                                } else viewModel.setExactReminders(true)
                            }
                        }
                    }
                }
                SettingsPage.ACCOUNTS -> {
                    item {
                        SettingsSection(
                            "Aula Virtual UNA",
                            caption = "La contraseña se envía por separado a Aula Virtual y Banner UNA y nunca se guarda. El token de Aula Virtual se cifra con Android Keystore.",
                        ) {
                            row { shapes ->
                                SegmentedListItem(
                                    colors = groupedListColors(),
                                    shapes = shapes,
                                    supportingContent = {
                                        Text(
                                            when {
                                                !state.moodle.connected -> "Importa tareas y próximos eventos de tus cursos."
                                                state.moodle.lastSyncEpoch != null -> "Última sincronización: ${formatSyncTime(state.moodle.lastSyncEpoch)}"
                                                else -> "Cuenta conectada; aún no se ha completado una sincronización."
                                            },
                                        )
                                    },
                                    trailingContent = {
                                        when {
                                            state.moodle.busy -> RowLoadingIndicator()
                                            state.moodle.connected -> Column(horizontalAlignment = Alignment.End) {
                                                TextButton(onClick = viewModel::syncMoodle) { Text("Sincronizar") }
                                                TextButton(onClick = viewModel::disconnectMoodle) { Text("Desconectar") }
                                            }
                                            else -> FilledTonalButton(onClick = { showMoodleDialog = true }) { Text("Conectar") }
                                        }
                                    },
                                ) {
                                    Text(if (state.moodle.connected) state.moodle.displayName ?: "Cuenta conectada" else "Conectar Aula Virtual")
                                }
                            }
                        }
                    }
                    if (state.moodle.connected) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AcademicProgressCard(
                                state = state.progress,
                                terms = state.progressTerms,
                                filter = state.settings.academicProgressFilter,
                                onSetFilter = viewModel::setAcademicProgressFilter,
                                onRefresh = { showProgressDialog = true },
                            )
                            SectionCaption("El avance se consulta solo cuando lo solicitas. Mi Campus conserva únicamente resúmenes agregados por período, cifrados localmente; no guarda tu contraseña, cookies, notas ni páginas de Banner.")
                        }
                    }
                }
                SettingsPage.AI -> item {
                    SettingsSection(
                        "Procesamiento",
                        caption = "Puedes activar el procesamiento local y la API de Google por separado. La nube nunca se usa automáticamente: cada lote exige consentimiento y solo se envía el texto extraído, no el archivo.",
                    ) {
                        val localDescription = when (val availability = state.localAiAvailability) {
                            LocalAiAvailability.Checking -> "Comprobando compatibilidad con Gemini Nano"
                            is LocalAiAvailability.Available -> availability.modelName
                                ?.takeIf(String::isNotBlank)
                                ?.let { "Disponible en este dispositivo · $it" }
                                ?: "Disponible en este dispositivo"
                            LocalAiAvailability.Downloadable -> "Compatible; el modelo se descargará con tu confirmación"
                            LocalAiAvailability.Downloading -> "Gemini Nano se está descargando"
                            LocalAiAvailability.Unavailable -> "No compatible o AICore aún no está disponible"
                            LocalAiAvailability.CheckFailed -> "No se pudo comprobar la compatibilidad"
                        }
                        row { shapes ->
                            SettingSwitchRow(
                                shapes = shapes,
                                label = "Modelo local (Gemini Nano)",
                                checked = state.settings.localAiEnabled,
                                enabled = state.localAiAvailability.isCompatible,
                                supportingText = localDescription,
                                testTag = "local-ai-switch",
                            ) { viewModel.setLocalAiEnabled(it) }
                        }
                        if (state.localAiAvailability == LocalAiAvailability.Unavailable || state.localAiAvailability == LocalAiAvailability.CheckFailed) row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                onClick = viewModel::refreshLocalAiCapability,
                                shapes = shapes,
                                leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            ) { Text("Volver a comprobar") }
                        }
                        row { shapes ->
                            SettingSwitchRow(
                                shapes = shapes,
                                label = "API de Google Gemini (nube)",
                                checked = state.settings.cloudAiEnabled,
                                supportingText = "Requiere tu clave y consentimiento para cada lote",
                                testTag = "cloud-ai-switch",
                            ) { viewModel.setCloudAiEnabled(it) }
                        }
                        row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                shapes = shapes,
                                enabled = state.settings.cloudAiEnabled,
                                supportingContent = { Text("Se cifra con Android Keystore y no entra en copias de seguridad.") },
                                leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                                trailingContent = {
                                    if (state.keyPresent) IconButton(onClick = viewModel::clearKey, enabled = state.settings.cloudAiEnabled) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar clave") }
                                    else TextButton(onClick = { showKeyDialog = true }, enabled = state.settings.cloudAiEnabled) { Text("Configurar") }
                                },
                            ) { Text(if (state.keyPresent) "Clave de Gemini configurada" else "Configurar clave propia") }
                        }
                    }
                }
                SettingsPage.ADVANCED -> item {
                    SettingsSection("Diagnóstico de importación") {
                        row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                shapes = shapes,
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    stateDescription = nanoSelfTestDescription(state.nanoSelfTest)
                                },
                                supportingContent = { Text(nanoSelfTestDescription(state.nanoSelfTest)) },
                                trailingContent = {
                                    if (state.nanoSelfTest == NanoSelfTestUiState.Running) {
                                        RowLoadingIndicator()
                                    } else {
                                        TextButton(
                                            onClick = viewModel::runNanoSelfTest,
                                            modifier = Modifier.testTag("nano-self-test"),
                                        ) { Text("Probar") }
                                    }
                                },
                            ) { Text("Probar Gemini Nano") }
                        }
                        row { shapes ->
                            SegmentedListItem(
                                colors = groupedListColors(),
                                shapes = shapes,
                                supportingContent = { Text("Incluye los últimos 10 intentos y pruebas sin texto de PDF, prompts, respuestas ni claves.") },
                                trailingContent = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        TextButton(onClick = viewModel::exportDiagnostics) { Text("Exportar diagnóstico") }
                                        TextButton(onClick = viewModel::clearDiagnostics) { Text("Borrar diagnóstico") }
                                    }
                                },
                            ) { Text("Registro técnico privado") }
                        }
                    }
                }
                SettingsPage.ABOUT -> item {
                    SettingsSection("Mi Campus") {
                        row { shapes ->
                            val updateText = when {
                                updateUiState.checking -> "Buscando actualizaciones…"
                                updateUiState.download is UpdateDownloadState.Completed -> "Actualización descargada; lista para instalar"
                                updateUiState.message != null -> updateUiState.message.orEmpty()
                                updateUiState.available != null -> "Hay una versión ${updateUiState.available?.version} disponible"
                                else -> null
                            }
                            SegmentedListItem(
                                colors = groupedListColors(),
                                shapes = shapes,
                                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                supportingContent = updateText?.let { text -> { Text(text) } },
                                trailingContent = {
                                    when {
                                        updateUiState.checking -> RowLoadingIndicator()
                                        updateUiState.download is UpdateDownloadState.Completed ->
                                            FilledTonalButton(onClick = onInstallUpdate) { Text("Instalar") }
                                        else -> TextButton(onClick = { update.checkForUpdate(manual = true) }) { Text("Buscar actualizaciones") }
                                    }
                                },
                            ) { Text("Versión ${BuildConfig.VERSION_NAME}") }
                        }
                    }
                }
            }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp)) } }
        }
    }
}

/** One Ajustes page: large collapsing title, back arrow and the `settings-list` the tests scroll through. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsPageScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().testTag("settings-list"),
            contentPadding = overlayContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

/** Front-page row: icon, page title, live status summary and a chevron into the sub-page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageRow(shapes: ListItemShapes, icon: ImageVector, page: SettingsPage, summary: String, onOpen: (SettingsPage) -> Unit) {
    SegmentedListItem(
        colors = groupedListColors(),
        onClick = { onOpen(page) },
        shapes = shapes,
        modifier = Modifier.testTag("settings-page-${page.name.lowercase()}"),
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Text(summary) },
    ) { Text(page.title) }
}

/** Surfaces the system block above everything else; the Recordatorios page keeps its own explanation row. */
@Composable
private fun NotificationsBlockedBanner(onEnable: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("notifications-blocked-banner"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Los recordatorios están bloqueados", style = MaterialTheme.typography.titleSmall)
                Text("Actívalos para que te avisemos antes de clases y entregas", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onEnable) { Text("Activar") }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Atrás") }
}

/** Scaffold padding plus the horizontal gutter; bottom keeps the navigation-bar inset so the last row isn't covered. */
@Composable
private fun overlayContentPadding(innerPadding: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
        end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
        top = innerPadding.calculateTopPadding() + 8.dp,
        bottom = innerPadding.calculateBottomPadding() + 24.dp,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowLoadingIndicator() {
    LoadingIndicator(Modifier.padding(8.dp).size(32.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeModeButtonGroup(selected: ThemeMode, onSelect: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val modes = ThemeMode.entries
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = selected == mode,
                onCheckedChange = {
                    if (selected != mode) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(mode)
                    }
                },
                modifier = Modifier.weight(1f),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (selected == mode) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                Text(themeLabel(mode), maxLines = 1)
            }
        }
    }
}

private fun nanoSelfTestDescription(state: NanoSelfTestUiState): String = when (state) {
    NanoSelfTestUiState.Idle -> "Comprueba localmente disponibilidad, modelo, límites de tokens y una generación mínima."
    NanoSelfTestUiState.Running -> "Probando Gemini Nano en este dispositivo"
    is NanoSelfTestUiState.Success -> buildString {
        append("Prueba completada")
        state.modelName?.takeIf(String::isNotBlank)?.let { append(" · $it") }
        append(" · ${state.inputTokens}/${state.tokenLimit} tokens")
    }
    is NanoSelfTestUiState.RequiresDownload -> when (state.capability) {
        NanoCapability.DOWNLOADABLE -> "Compatible; la prueba requiere descargar el modelo desde una importación."
        NanoCapability.DOWNLOADING -> "Compatible; Gemini Nano todavía se está descargando."
        else -> "La prueba requiere que Gemini Nano esté disponible."
    }
    NanoSelfTestUiState.Unavailable -> "Gemini Nano no está disponible según AICore."
    is NanoSelfTestUiState.Failure -> "La prueba falló (${state.failure.name.lowercase()}); exporta el diagnóstico."
}

private class SettingsSectionScope {
    val rows = mutableListOf<@Composable (ListItemShapes) -> Unit>()
    fun row(content: @Composable (ListItemShapes) -> Unit) {
        rows += content
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsSection(title: String, caption: String? = null, rows: SettingsSectionScope.() -> Unit) {
    val content = SettingsSectionScope().apply(rows).rows
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            content.forEachIndexed { index, row -> row(ListItemDefaults.segmentedShapes(index, content.size)) }
        }
        caption?.let { SectionCaption(it) }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingSwitchRow(
    shapes: ListItemShapes,
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    supportingText: String? = null,
    testTag: String? = null,
    onChecked: (Boolean) -> Unit,
) {
    SegmentedListItem(
        colors = groupedListColors(),
        shapes = shapes,
        enabled = enabled,
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = supportingText ?: label },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
                modifier = if (testTag == null) Modifier else Modifier.testTag(testTag),
            )
        },
    ) { Text(label) }
}

private enum class NotificationAvailability(val explanation: String) {
    AVAILABLE(""),
    APP_BLOCKED("Las notificaciones de Mi Campus están bloqueadas. Actívalas en los ajustes del sistema para recibir recordatorios."),
    CHANNEL_BLOCKED("El canal de recordatorios académicos está bloqueado. Actívalo en los ajustes del sistema para recibir recordatorios."),
}

private fun notificationAvailability(context: Context): NotificationAvailability {
    if (
        Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return NotificationAvailability.APP_BLOCKED
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return NotificationAvailability.APP_BLOCKED
    if (Build.VERSION.SDK_INT >= 26) {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderScheduler.CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return NotificationAvailability.CHANNEL_BLOCKED
    }
    return NotificationAvailability.AVAILABLE
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clave de Gemini") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Usa una clave propia. No se mostrará ni se registrará en logs.")
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MoodleLoginDialog(
    title: String = "Conectar Aula Virtual",
    confirmLabel: String = "Conectar",
    onDismiss: () -> Unit,
    onConnect: (String, CharArray) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Usa tus credenciales institucionales UNA. No se guardarán.")
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nombre de usuario") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val secret = password.toCharArray()
                    password = ""
                    onConnect(username.trim(), secret)
                },
                enabled = username.isNotBlank() && password.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AcademicProgressCard(
    state: AcademicProgressUiState,
    terms: List<AcademicTermProgress>,
    filter: AcademicProgressFilter,
    onSetFilter: (AcademicProgressFilter) -> Unit,
    onRefresh: () -> Unit,
) {
    var showFilters by remember { mutableStateOf(false) }
    if (showFilters) AcademicProgressFilterDialog(terms, filter, onSetFilter, onDismiss = { showFilters = false })
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("academic-progress-card")
            .semantics { stateDescription = "${academicProgressDescription(state)}. ${progressFilterSummary(filter)}" },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Progreso académico",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val activeFilters = activeProgressFilterCount(filter)
                BadgedBox(badge = { if (activeFilters > 0) Badge { Text(activeFilters.toString()) } }) {
                    OutlinedButton(onClick = { showFilters = true }, modifier = Modifier.testTag("academic-progress-filter")) {
                        Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Filtrar")
                    }
                }
            }
            Text(progressFilterSummary(filter), style = MaterialTheme.typography.bodySmall)
            when (state) {
                AcademicProgressUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("Consultando Banner…")
                }
                AcademicProgressUiState.Hidden -> Unit
                is AcademicProgressUiState.Unavailable -> { Text(state.message); TextButton(onClick = onRefresh) { Text("Actualizar progreso") } }
                is AcademicProgressUiState.Available -> ProgressDetails(
                    state.progress,
                    emptyProgressMessage(terms, filter),
                    null,
                    onRefresh,
                )
                is AcademicProgressUiState.StaleError -> ProgressDetails(
                    state.progress,
                    emptyProgressMessage(terms, filter),
                    state.message,
                    onRefresh,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressDetails(
    progress: cr.micampus.app.core.model.AcademicProgress,
    emptyMessage: String,
    warning: String?,
    onRefresh: () -> Unit,
) {
    val percentage = progress.percentage
    if (percentage != null) {
        val locale = LocalConfiguration.current.locales[0]
        Text(String.format(locale, "%.1f %%", percentage), style = MaterialTheme.typography.displaySmallEmphasized)
        LinearWavyProgressIndicator(
            progress = { (percentage / 100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text("${formatCredits(progress.approvedCredits)} de ${formatCredits(progress.attemptedCredits)} créditos aprobados", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(emptyMessage)
    }
    if (progress.unclassifiedResults > 0) {
        val resultLabel = if (progress.unclassifiedResults == 1) "resultado" else "resultados"
        val excludedLabel = if (progress.unclassifiedResults == 1) "fue excluido" else "fueron excluidos"
        Text("${progress.unclassifiedResults} $resultLabel con calificación sin clasificar $excludedLabel", style = MaterialTheme.typography.bodySmall)
    }
    Text("Actualizado: ${formatSyncTime(progress.updatedAtEpoch)}", style = MaterialTheme.typography.bodySmall)
    warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = onRefresh) { Text(if (warning == null) "Actualizar progreso" else "Reintentar") }
}

private fun activeProgressFilterCount(filter: AcademicProgressFilter): Int =
    (if (filter.year != null) 1 else 0) +
        (if (filter.cycle != null) 1 else 0) +
        (if (filter.unfinishedPolicy != UnfinishedCoursePolicy.EXCLUDE) 1 else 0)

@Composable
private fun AcademicProgressFilterDialog(
    terms: List<AcademicTermProgress>,
    current: AcademicProgressFilter,
    onApply: (AcademicProgressFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedYear by remember(current) { mutableStateOf(current.year) }
    var selectedCycle by remember(current) { mutableStateOf(current.cycle) }
    var policy by remember(current) { mutableStateOf(current.unfinishedPolicy) }
    val years = terms.mapNotNull { it.year }.distinct().sortedDescending()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtrar progreso académico") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Año", style = MaterialTheme.typography.labelLarge)
                FilterChoice("Todos", selectedYear == null) { selectedYear = null }
                years.forEach { year -> FilterChoice(year.toString(), selectedYear == year) { selectedYear = year } }
                Text("Ciclo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FilterChoice("Todos", selectedCycle == null) { selectedCycle = null }
                FilterChoice("I", selectedCycle == AcademicCycle.I) { selectedCycle = AcademicCycle.I }
                FilterChoice("II", selectedCycle == AcademicCycle.II) { selectedCycle = AcademicCycle.II }
                Text("Cursos sin nota final", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FilterChoice("Excluir", policy == UnfinishedCoursePolicy.EXCLUDE) { policy = UnfinishedCoursePolicy.EXCLUDE }
                FilterChoice("Contar como reprobados", policy == UnfinishedCoursePolicy.AS_FAILED) { policy = UnfinishedCoursePolicy.AS_FAILED }
                FilterChoice("Contar como aprobados", policy == UnfinishedCoursePolicy.AS_PASSED) { policy = UnfinishedCoursePolicy.AS_PASSED }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(AcademicProgressFilter(selectedYear, selectedCycle, policy)); onDismiss() }) { Text("Aplicar") } },
        dismissButton = {
            TextButton(onClick = { onApply(AcademicProgressFilter()); onDismiss() }) { Text("Restablecer") }
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun progressFilterSummary(filter: AcademicProgressFilter): String = buildList {
    add(filter.year?.toString() ?: "Todos los años")
    add(filter.cycle?.let { "Ciclo ${it.name}" } ?: "Todos los ciclos")
    add(when (filter.unfinishedPolicy) {
        UnfinishedCoursePolicy.EXCLUDE -> "Sin nota: excluir"
        UnfinishedCoursePolicy.AS_FAILED -> "Sin nota: reprobados"
        UnfinishedCoursePolicy.AS_PASSED -> "Sin nota: aprobados"
    })
}.joinToString(" · ")

private fun emptyProgressMessage(terms: List<AcademicTermProgress>, filter: AcademicProgressFilter): String {
    val hasMatchingTerm = terms.any { term ->
        (filter.year == null || term.year == filter.year) && (filter.cycle == null || term.cycle == filter.cycle)
    }
    return if (hasMatchingTerm) "No hay créditos incluidos con este filtro" else "No hay datos para este período"
}

private fun academicProgressDescription(state: AcademicProgressUiState): String = when (state) {
    AcademicProgressUiState.Hidden -> "Progreso académico oculto"
    AcademicProgressUiState.Loading -> "Consultando progreso académico en Banner"
    is AcademicProgressUiState.Unavailable -> "Progreso académico no disponible. ${state.message}"
    is AcademicProgressUiState.Available -> state.progress.percentage
        ?.let { String.format(Locale.getDefault(), "Progreso académico %.1f por ciento", it) }
        ?: "Progreso académico no disponible"
    is AcademicProgressUiState.StaleError -> state.progress.percentage
        ?.let { String.format(Locale.getDefault(), "Progreso académico desactualizado %.1f por ciento", it) }
        ?: "Progreso académico desactualizado"
}

private fun formatCredits(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun formatSyncTime(epoch: Long): String = DateTimeFormatter.ofPattern("d/M/yyyy HH:mm")
    .format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun themeLabel(mode: ThemeMode) = when (mode) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Oscuro" }
