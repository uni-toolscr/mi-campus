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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cr.micampus.app.core.model.ThemeMode

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
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Section("Instituciones") }
        item { SettingSwitch("Universidad de Costa Rica", state.settings.ucrEnabled) { viewModel.setInstitutions(it, state.settings.unaEnabled) } }
        item { SettingSwitch("Universidad Nacional", state.settings.unaEnabled) { viewModel.setInstitutions(state.settings.ucrEnabled, it) } }
        item { Section("Apariencia") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { mode ->
                    FilterChip(selected = state.settings.themeMode == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(themeLabel(mode)) })
                }
            }
        }
        item { Section("Recordatorios") }
        item {
            SettingSwitch("Notificaciones de eventos", state.settings.remindersEnabled) { enabled ->
                if (!enabled) viewModel.setReminders(false)
                else if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else viewModel.setReminders(true)
            }
        }
        item {
            SettingSwitch("Entrega exacta", state.settings.exactReminders, enabled = state.settings.remindersEnabled) { enabled ->
                if (!enabled) viewModel.setExactReminders(false)
                else if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                    exactLauncher.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                } else viewModel.setExactReminders(true)
            }
        }
        item { Text("Sin acceso exacto, Mi Campus usa WorkManager y la entrega puede retrasarse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Section("IA y privacidad") }
        item { SettingSwitch("Permitir IA opcional", state.settings.aiEnabled) { viewModel.setAi(it) } }
        item {
            ListItem(
                headlineContent = { Text(if (state.keyPresent) "Clave de Gemini configurada" else "Configurar clave propia") },
                supportingContent = { Text("Se cifra con Android Keystore y no entra en copias de seguridad.") },
                leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                trailingContent = {
                    if (state.keyPresent) IconButton(onClick = viewModel::clearKey) { Icon(Icons.Outlined.Delete, contentDescription = "Eliminar clave") }
                    else TextButton(onClick = { showKeyDialog = true }) { Text("Configurar") }
                },
            )
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
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled) },
    )
}

@Composable private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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
