package cr.micampus.app.feature.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cr.micampus.app.data.update.UpdateDownloadState

/**
 * Single, repeatable install trigger derived from `download`'s persistent Completed(file) state
 * (not a one-shot effect), so a permission redirect or a "skip" tap never strands the user with
 * a downloaded APK and no way to finish installing it.
 */
@Composable
fun rememberUpdateInstallAction(update: UpdateViewModel): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by update.state.collectAsStateWithLifecycle()
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val attemptInstall: () -> Unit = attempt@{
        val file = (state.download as? UpdateDownloadState.Completed)?.file ?: return@attempt
        if (!context.packageManager.canRequestPackageInstalls()) {
            installLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            return@attempt
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
    }

    // Auto-fire once per completed download, e.g. permission was already granted previously.
    LaunchedEffect(state.download) {
        if (state.download is UpdateDownloadState.Completed) attemptInstall()
    }

    // Auto-resume: if the user just granted "install unknown apps" and came back, finish the
    // job without an extra tap. Mirrors the notificationAvailability ON_RESUME pattern used for
    // reminders in SettingsScreen.kt.
    var canInstall by remember(context) { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previous = canInstall
                val current = context.packageManager.canRequestPackageInstalls()
                canInstall = current
                if (!previous && current) attemptInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return attemptInstall
}
