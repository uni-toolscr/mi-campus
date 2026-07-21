package cr.micampus.app.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateDownloadState
    data class Completed(val file: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}

class UpdateDownloader(context: android.content.Context) {
    private val dir = File(context.noBackupFilesDir, "updates").apply { mkdirs() }

    suspend fun download(update: AppUpdate, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): File = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        // Fixed name: the tag-derived version string is not sanitized for filesystem safety,
        // and dir.listFiles() above already guarantees this is the only file present.
        val target = File(dir, "micampus-update.apk")
        try {
            val totalBytes = connection.contentLengthLong
            var bytesRead = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytesRead += count
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
            target
        } finally {
            connection.disconnect()
        }
    }
}
