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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.ThemeMode
import cr.micampus.app.core.model.AcademicCycle
import cr.micampus.app.core.model.AcademicProgressFilter
import cr.micampus.app.core.model.AcademicTermProgress
import cr.micampus.app.core.model.UnfinishedCoursePolicy
import cr.micampus.app.data.ai.NanoCapability
import cr.micampus.app.feature.update.UpdateViewModel
import cr.micampus.app.platform.reminders.ReminderScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, viewModel: SettingsViewModel, update: UpdateViewModel, onBack: () -> Unit = {}) {
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
    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
        contentPadding = edgeToEdgeContentPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Atrás") }
                Text("Ajustes", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingsSection("Instituciones") {
                SettingSwitchRow("Universidad de Costa Rica", state.settings.ucrEnabled) { viewModel.setInstitutions(it, state.settings.unaEnabled) }
                SettingSwitchRow("Universidad Nacional", state.settings.unaEnabled, isLast = true) { viewModel.setInstitutions(state.settings.ucrEnabled, it) }
            }
        }
        item {
            SettingsSection("Actualizaciones") {
                GroupedListItem(
                    isLast = true,
                    headlineContent = { Text("Versión ${BuildConfig.VERSION_NAME}") },
                    supportingContent = {
                        val text = when {
                            updateUiState.checking -> "Buscando actualizaciones…"
                            updateUiState.message != null -> updateUiState.message.orEmpty()
                            updateUiState.available != null -> "Hay una versión ${updateUiState.available?.version} disponible"
                            else -> ""
                        }
                        Text(text)
                    },
                    trailingContent = {
                        if (updateUiState.checking) CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                        else TextButton(onClick = { update.checkForUpdate(manual = true) }) { Text("Buscar actualizaciones") }
                    },
                )
            }
        }
        item {
            SettingsSection("Apariencia") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                    ThemeMode.values().forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.values().size),
                            label = { Text(themeLabel(mode)) },
                        )
                    }
                }
                SettingSwitchRow("Formato de 12 horas (a. m./p. m.)", state.settings.use12hClock, isLast = true) { viewModel.setUse12hClock(it) }
            }
        }
        item {
            SettingsSection("Recordatorios") {
                SettingSwitchRow("Notificaciones de eventos", state.settings.remindersEnabled) { enabled ->
                    if (!enabled) viewModel.setReminders(false)
                    else if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.setReminders(true)
                }
                if (notificationAvailability != NotificationAvailability.AVAILABLE) {
                    Text(
                        notificationAvailability.explanation,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    ) { Text("Abrir ajustes de notificaciones") }
                }
                SettingSwitchRow("Entrega exacta", state.settings.exactReminders, enabled = state.settings.remindersEnabled, isLast = true) { enabled ->
                    if (!enabled) viewModel.setExactReminders(false)
                    else if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                        exactLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                    } else viewModel.setExactReminders(true)
                }
            }
        }
        item { Text("Sin acceso exacto, Mi Campus usa WorkManager y la entrega puede retrasarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            SettingsSection("Aula Virtual UNA") {
                GroupedListItem(
                    isLast = true,
                    headlineContent = {
                        Text(if (state.moodle.connected) state.moodle.displayName ?: "Cuenta conectada" else "Conectar Aula Virtual")
                    },
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
                            state.moodle.busy -> CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                            state.moodle.connected -> Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = viewModel::syncMoodle) { Text("Sincronizar") }
                                TextButton(onClick = viewModel::disconnectMoodle) { Text("Desconectar") }
                            }
                            else -> TextButton(onClick = { showMoodleDialog = true }) { Text("Conectar") }
                        }
                    },
                )
            }
        }
        item {
            Text(
                "La contraseña se envía por separado a Aula Virtual y Banner UNA y nunca se guarda. El token de Aula Virtual se cifra con Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.moodle.connected) item {
            AcademicProgressCard(
                state = state.progress,
                terms = state.progressTerms,
                filter = state.settings.academicProgressFilter,
                onSetFilter = viewModel::setAcademicProgressFilter,
                onRefresh = { showProgressDialog = true },
            )
        }
        if (state.moodle.connected) item {
            Text(
                "El avance se consulta solo cuando lo solicitas. Mi Campus conserva únicamente resúmenes agregados por período, cifrados localmente; no guarda tu contraseña, cookies, notas ni páginas de Banner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SettingsSection("IA y privacidad") {
                val localCompatible = state.localAiAvailability.isCompatible
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
                SettingSwitchRow(
                    label = "Modelo local (Gemini Nano)",
                    checked = state.settings.localAiEnabled,
                    enabled = localCompatible,
                    supportingText = localDescription,
                    testTag = "local-ai-switch",
                ) { viewModel.setLocalAiEnabled(it) }
                if (state.localAiAvailability == LocalAiAvailability.Unavailable || state.localAiAvailability == LocalAiAvailability.CheckFailed) {
                    TextButton(onClick = viewModel::refreshLocalAiCapability) { Text("Volver a comprobar") }
                }
                SettingSwitchRow(
                    label = "API de Google Gemini (nube)",
                    checked = state.settings.cloudAiEnabled,
                    supportingText = "Requiere tu clave y consentimiento para cada lote",
                    testTag = "cloud-ai-switch",
                ) { viewModel.setCloudAiEnabled(it) }
                GroupedListItem(
                    modifier = Modifier.alpha(if (state.settings.cloudAiEnabled) 1f else DISABLED_ALPHA),
                    isLast = true,
                    headlineContent = { Text(if (state.keyPresent) "Clave de Gemini configurada" else "Configurar clave propia") },
                    supportingContent = { Text("Se cifra con Android Keystore y no entra en copias de seguridad.") },
                    leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    trailingContent = {
                        if (state.keyPresent) IconButton(onClick = viewModel::clearKey, enabled = state.settings.cloudAiEnabled) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar clave") }
                        else TextButton(onClick = { showKeyDialog = true }, enabled = state.settings.cloudAiEnabled) { Text("Configurar") }
                    },
                )
            }
        }
        item {
            Text(
                "Puedes activar el procesamiento local y la API de Google por separado. La nube nunca se usa automáticamente: cada lote exige consentimiento y solo se envía el texto extraído, no el archivo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.diagnosticsAvailable) {
            item {
                SettingsSection("Diagnóstico de importación") {
                    GroupedListItem(
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            stateDescription = nanoSelfTestDescription(state.nanoSelfTest)
                        },
                        isLast = false,
                        headlineContent = { Text("Probar Gemini Nano") },
                        supportingContent = { Text(nanoSelfTestDescription(state.nanoSelfTest)) },
                        trailingContent = {
                            if (state.nanoSelfTest == NanoSelfTestUiState.Running) {
                                CircularProgressIndicator(Modifier.padding(12.dp))
                            } else {
                                TextButton(
                                    onClick = viewModel::runNanoSelfTest,
                                    modifier = Modifier.testTag("nano-self-test"),
                                ) { Text("Probar") }
                            }
                        },
                    )
                    GroupedListItem(
                        isLast = true,
                        headlineContent = { Text("Registro técnico privado") },
                        supportingContent = { Text("Incluye los últimos 10 intentos y pruebas sin texto de PDF, prompts, respuestas ni claves.") },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(onClick = viewModel::exportDiagnostics) { Text("Exportar diagnóstico") }
                                TextButton(onClick = viewModel::clearDiagnostics) { Text("Borrar diagnóstico") }
                            }
                        },
                    )
                }
            }
        }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
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

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    supportingText: String? = null,
    testTag: String? = null,
    isLast: Boolean = false,
    onChecked: (Boolean) -> Unit,
) {
    GroupedListItem(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics(mergeDescendants = true) { stateDescription = supportingText ?: label },
        isLast = isLast,
        headlineContent = { Text(label) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
                modifier = if (testTag == null) Modifier else Modifier.testTag(testTag),
            )
        },
    )
}

/** A single row of a grouped rounded card list. The last row in a section gets larger bottom corners. */
@Composable
private fun GroupedListItem(
    modifier: Modifier = Modifier,
    isLast: Boolean,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = if (isLast) 20.dp else 4.dp, bottomEnd = if (isLast) 20.dp else 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            headlineContent = headlineContent,
            supportingContent = supportingContent,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

private const val DISABLED_ALPHA = 0.48f

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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("academic-progress-card")
            .semantics { stateDescription = "${academicProgressDescription(state)}. ${progressFilterSummary(filter)}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Progreso académico", style = MaterialTheme.typography.titleMedium)
            Text(progressFilterSummary(filter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state) {
                AcademicProgressUiState.Loading -> { CircularProgressIndicator(); Text("Consultando Banner…") }
                AcademicProgressUiState.Hidden -> Unit
                is AcademicProgressUiState.Unavailable -> { Text(state.message); TextButton(onClick = onRefresh) { Text("Actualizar progreso") } }
                is AcademicProgressUiState.Available -> ProgressDetails(
                    state.progress,
                    emptyProgressMessage(terms, filter),
                    null,
                    onRefresh,
                    onFilter = { showFilters = true },
                )
                is AcademicProgressUiState.StaleError -> ProgressDetails(
                    state.progress,
                    emptyProgressMessage(terms, filter),
                    state.message,
                    onRefresh,
                    onFilter = { showFilters = true },
                )
            }
        }
    }
}

@Composable
private fun ProgressDetails(
    progress: cr.micampus.app.core.model.AcademicProgress,
    emptyMessage: String,
    warning: String?,
    onRefresh: () -> Unit,
    onFilter: () -> Unit,
) {
    val percentage = progress.percentage
    Text(percentage?.let { String.format(Locale.getDefault(), "%.1f %%", it) } ?: emptyMessage)
    if (percentage != null) {
        LinearProgressIndicator(progress = { (percentage / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
        Text("${formatCredits(progress.approvedCredits)} de ${formatCredits(progress.attemptedCredits)} créditos aprobados", style = MaterialTheme.typography.bodySmall)
    }
    if (progress.unclassifiedResults > 0) {
        val resultLabel = if (progress.unclassifiedResults == 1) "resultado" else "resultados"
        val excludedLabel = if (progress.unclassifiedResults == 1) "fue excluido" else "fueron excluidos"
        Text("${progress.unclassifiedResults} $resultLabel con calificación sin clasificar $excludedLabel", style = MaterialTheme.typography.bodySmall)
    }
    Text("Actualizado: ${formatSyncTime(progress.updatedAtEpoch)}", style = MaterialTheme.typography.bodySmall)
    warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = onFilter) { Text("Filtrar") }
    TextButton(onClick = onRefresh) { Text(if (warning == null) "Actualizar progreso" else "Reintentar") }
}

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
