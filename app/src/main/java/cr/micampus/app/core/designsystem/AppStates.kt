package cr.micampus.app.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal safe-drawing inset (status bar / cutout / waterfall) for top-level screens that own their padding. */
@Composable
fun Modifier.safeHorizontalInsets(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

/** LazyColumn content padding that reserves space for the status bar / cutout on top while letting content scroll behind it. */
@Composable
fun edgeToEdgeContentPadding(vertical: Dp = 20.dp): PaddingValues {
    val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    return PaddingValues(top = top + vertical, bottom = vertical)
}

@Composable
fun LoadingState(label: String = "Cargando") {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun EmptyState(title: String, body: String, offline: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (offline) Icons.Outlined.CloudOff else Icons.Outlined.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorState(message: String) = EmptyState("No se pudo cargar", message, offline = true)
