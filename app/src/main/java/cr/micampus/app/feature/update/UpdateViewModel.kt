package cr.micampus.app.feature.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cr.micampus.app.data.local.SettingsStore
import cr.micampus.app.data.update.AppUpdate
import cr.micampus.app.data.update.UpdateChecker
import cr.micampus.app.data.update.UpdateDownloadState
import cr.micampus.app.data.update.UpdateDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpdateUiState(
    val checking: Boolean = false,
    val available: AppUpdate? = null,
    val dismissedVersion: String? = null,
    val download: UpdateDownloadState = UpdateDownloadState.Idle,
    val message: String? = null,
)

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val settings: SettingsStore,
    private val currentVersion: String,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s -> _state.value = _state.value.copy(dismissedVersion = s.dismissedUpdateVersion) }
        }
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, message = null)
        viewModelScope.launch {
            val result = try { checker.checkForUpdate(currentVersion) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            _state.value = _state.value.copy(
                checking = false,
                available = result,
                message = if (manual && result == null) "Ya tienes la última versión" else _state.value.message,
            )
        }
    }

    fun startDownload() {
        val update = _state.value.available ?: return
        if (_state.value.download is UpdateDownloadState.Downloading) return
        _state.value = _state.value.copy(download = UpdateDownloadState.Downloading(0, 0))
        viewModelScope.launch {
            try {
                val file = downloader.download(update) { read, total ->
                    _state.value = _state.value.copy(download = UpdateDownloadState.Downloading(read, total))
                }
                _state.value = _state.value.copy(download = UpdateDownloadState.Completed(file))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(download = UpdateDownloadState.Failed(e.message ?: "No se pudo descargar la actualización"))
            }
        }
    }

    fun dismiss() {
        val version = _state.value.available?.version ?: return
        viewModelScope.launch { settings.setDismissedUpdateVersion(version) }
    }
}
