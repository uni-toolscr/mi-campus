package cr.micampus.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cr.micampus.app.data.update.UpdateDownloadState

@Composable
fun UpdateAvailableDialog(state: UpdateUiState, onDownload: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val downloading = state.download is UpdateDownloadState.Downloading
    val completed = state.download is UpdateDownloadState.Completed
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Actualización disponible") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Versión ${state.available?.version}")
                val notes = state.available?.notes
                if (!notes.isNullOrBlank()) Text(notes, style = MaterialTheme.typography.bodySmall)
                when (val download = state.download) {
                    is UpdateDownloadState.Downloading -> {
                        if (download.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { download.bytesRead.toFloat() / download.totalBytes.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateDownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            Button(onClick = if (completed) onInstall else onDownload, enabled = !downloading) {
                Text(if (completed) "Instalar" else "Descargar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("Más tarde") }
        },
    )
}
