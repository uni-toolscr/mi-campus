package cr.micampus.app.feature.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cr.micampus.app.core.designsystem.edgeToEdgeContentPadding
import cr.micampus.app.core.designsystem.safeHorizontalInsets
import cr.micampus.app.core.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    var showKeyDialog by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.setReminders(granted) }
    val exactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        viewModel.setExactReminders(granted)
    }
    if (showKeyDialog) KeyDialog(onDismiss = { showKeyDialog = false }, onSave = { viewModel.saveKey(it); showKeyDialog = false })
    LazyColumn(
        Modifier.fillMaxSize().safeHorizontalInsets().padding(horizontal = 20.dp),
        contentPadding = edgeToEdgeContentPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text("Ajustes", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) }
        item {
            SettingsSection("Instituciones") {
                SettingSwitchRow("Universidad de Costa Rica", state.settings.ucrEnabled) { viewModel.setInstitutions(it, state.settings.unaEnabled) }
                SettingSwitchRow("Universidad Nacional", state.settings.unaEnabled, isLast = true) { viewModel.setInstitutions(state.settings.ucrEnabled, it) }
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
            }
        }
        item {
            SettingsSection("Recordatorios") {
                SettingSwitchRow("Notificaciones de eventos", state.settings.remindersEnabled) { enabled ->
                    if (!enabled) viewModel.setReminders(false)
                    else if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.setReminders(true)
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
            SettingsSection("IA y privacidad") {
                SettingSwitchRow("Permitir IA opcional", state.settings.aiEnabled) { viewModel.setAi(it) }
                GroupedListItem(
                    isLast = true,
                    headlineContent = { Text(if (state.keyPresent) "Clave de Gemini configurada" else "Configurar clave propia") },
                    supportingContent = { Text("Se cifra con Android Keystore y no entra en copias de seguridad.") },
                    leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                    trailingContent = {
                        if (state.keyPresent) IconButton(onClick = viewModel::clearKey) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar clave") }
                        else TextButton(onClick = { showKeyDialog = true }) { Text("Configurar") }
                    },
                )
            }
        }
        item {
            Text(
                "El procesamiento local se intenta primero. La nube nunca se usa automáticamente: cada PDF exige consentimiento y solo se envía el texto extraído, no el archivo.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, enabled: Boolean = true, isLast: Boolean = false, onChecked: (Boolean) -> Unit) {
    GroupedListItem(
        isLast = isLast,
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled) },
    )
}

/** A single row of a grouped rounded card list. The last row in a section gets larger bottom corners. */
@Composable
private fun GroupedListItem(
    isLast: Boolean,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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

private fun themeLabel(mode: ThemeMode) = when (mode) { ThemeMode.SYSTEM -> "Sistema"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Oscuro" }
